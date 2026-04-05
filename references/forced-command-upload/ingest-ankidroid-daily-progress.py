#!/usr/bin/env python3
"""Reference forced-command ingest for AnkiDroid daily progress snapshots."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import tempfile
from pathlib import Path
from typing import Any

MAX_BYTES = 256 * 1024
ALLOWED_WINDOW_KINDS = {
    "current_partial_anki_day",
    "previous_completed_anki_day",
    "last_non_empty_completed_anki_day",
}
ALLOWED_SNAPSHOT_TYPES = {"partial", "completed"}
REQUIRED_TOP_LEVEL_FIELDS = {
    "snapshot_type",
    "window_kind",
    "generated_at_epoch_ms",
    "window_start_epoch_ms",
    "window_end_epoch_ms_exclusive",
    "unique_cards_studied",
    "answer_events_total",
    "study_time_ms",
    "answer_buttons",
    "review_kinds",
    "active_day",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--landing-dir", required=True)
    parser.add_argument(
        "--expected-original-command",
        default="ankidroid-daily-progress-upload",
    )
    return parser.parse_args()


def fail(message: str, exit_code: int = 1) -> None:
    print(message, file=sys.stderr)
    raise SystemExit(exit_code)


def read_payload() -> bytes:
    payload = sys.stdin.buffer.read(MAX_BYTES + 1)
    if not payload:
        fail("empty payload", 2)
    if len(payload) > MAX_BYTES:
        fail(f"payload too large: {len(payload)} bytes", 2)
    return payload


def require_int(document: dict[str, Any], field: str) -> int:
    value = document.get(field)
    if not isinstance(value, int):
        fail(f"field {field!r} must be an integer", 2)
    return value


def require_non_negative_int(document: dict[str, Any], field: str) -> int:
    value = require_int(document, field)
    if value < 0:
        fail(f"field {field!r} must be >= 0", 2)
    return value


def require_mapping(document: dict[str, Any], field: str) -> dict[str, Any]:
    value = document.get(field)
    if not isinstance(value, dict):
        fail(f"field {field!r} must be an object", 2)
    return value


def validate_payload(document: dict[str, Any]) -> None:
    missing = sorted(REQUIRED_TOP_LEVEL_FIELDS - document.keys())
    if missing:
        fail(f"missing required fields: {', '.join(missing)}", 2)

    snapshot_type = document.get("snapshot_type")
    if snapshot_type not in ALLOWED_SNAPSHOT_TYPES:
        fail(f"unexpected snapshot_type: {snapshot_type!r}", 2)

    window_kind = document.get("window_kind")
    if window_kind not in ALLOWED_WINDOW_KINDS:
        fail(f"unexpected window_kind: {window_kind!r}", 2)

    generated_at = require_non_negative_int(document, "generated_at_epoch_ms")
    window_start = require_non_negative_int(document, "window_start_epoch_ms")
    window_end = require_non_negative_int(document, "window_end_epoch_ms_exclusive")
    unique_cards = require_non_negative_int(document, "unique_cards_studied")
    answer_events_total = require_non_negative_int(document, "answer_events_total")
    study_time = require_non_negative_int(document, "study_time_ms")

    if window_end <= window_start:
        fail("window_end_epoch_ms_exclusive must be greater than window_start_epoch_ms", 2)

    if generated_at < window_start:
        fail("generated_at_epoch_ms must not be earlier than window_start_epoch_ms", 2)

    answer_buttons = require_mapping(document, "answer_buttons")
    for field in ("again", "hard", "good", "easy"):
        require_non_negative_int(answer_buttons, field)

    review_kinds = require_mapping(document, "review_kinds")
    for field in ("learn", "review", "relearn", "filtered"):
        require_non_negative_int(review_kinds, field)

    active_day = document.get("active_day")
    if not isinstance(active_day, bool):
        fail("field 'active_day' must be a boolean", 2)

    if answer_events_total == 0 and active_day:
        fail("active_day=true is inconsistent with answer_events_total=0", 2)

    if unique_cards > answer_events_total and answer_events_total != 0:
        fail("unique_cards_studied cannot exceed answer_events_total", 2)

    if study_time < 0:
        fail("study_time_ms must be >= 0", 2)


def canonical_digest(document: dict[str, Any]) -> str:
    canonical = json.dumps(document, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(canonical).hexdigest()[:12]


def store_payload(landing_dir: Path, payload: bytes, document: dict[str, Any]) -> tuple[str, bool]:
    window_kind = document["window_kind"]
    window_start = document["window_start_epoch_ms"]
    window_end = document["window_end_epoch_ms_exclusive"]
    digest = canonical_digest(document)
    file_name = f"{window_kind}-{window_start}-{window_end}-{digest}.json"

    landing_dir.mkdir(parents=True, exist_ok=True)
    target = landing_dir / file_name
    if target.exists():
        return file_name, False

    with tempfile.NamedTemporaryFile(dir=landing_dir, prefix=".tmp-", suffix=".json", delete=False) as handle:
        handle.write(payload)
        temp_path = Path(handle.name)

    temp_path.replace(target)
    return file_name, True


def main() -> None:
    args = parse_args()
    actual_original_command = os.environ.get("SSH_ORIGINAL_COMMAND", "")
    if actual_original_command != args.expected_original_command:
        fail(
            f"unexpected SSH_ORIGINAL_COMMAND: {actual_original_command!r}",
            126,
        )

    payload = read_payload()
    try:
        document = json.loads(payload.decode("utf-8"))
    except UnicodeDecodeError as exception:
        fail(f"payload is not valid UTF-8: {exception}", 2)
    except json.JSONDecodeError as exception:
        fail(f"payload is not valid JSON: {exception}", 2)

    if not isinstance(document, dict):
        fail("top-level JSON value must be an object", 2)

    validate_payload(document)
    file_name, stored = store_payload(Path(args.landing_dir), payload, document)
    if stored:
        print(f"stored {file_name}")
    else:
        print(f"duplicate {file_name}")


if __name__ == "__main__":
    main()
