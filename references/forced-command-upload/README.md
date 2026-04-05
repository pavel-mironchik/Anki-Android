# Forced-command SSH upload reference

This directory contains reference artifacts for the first developer-only transport pass from AnkiDroid to a fixed SSH ingest.

## Intended protocol

- Client opens SSH to a dedicated ingest account.
- Client authenticates with public-key auth.
- Client opens a single `exec` channel with original command:
  - `ankidroid-daily-progress-upload`
- Server-side `authorized_keys` forces the real ingest command regardless of what the client asked for.
- Ingest reads exactly one JSON blob from `stdin`.
- Client does **not** choose any remote path.
- Server stores the blob in a fixed landing directory.

## Current app-side assumptions for this pass

- One trigger only: **Upload current partial day JSON over SSH now**.
- Dev-only config is stored in developer options.
- Expected auth material for this pass: an **unencrypted** private key pasted into the dev preference.
- `known_hosts` is a single pasted line used for strict host key checking.
- No SFTP, no shell session, no arbitrary remote command, no arbitrary remote file path.

## File naming and idempotency

Reference ingest uses:

- `window_kind`
- `window_start_epoch_ms`
- `window_end_epoch_ms_exclusive`
- SHA-256 of canonicalized JSON

Resulting file name:

- `<window_kind>-<window_start>-<window_end>-<sha12>.json`

Behavior:

- identical payload resent => same file name => treated as duplicate/no-op
- changed payload for the same logical window => different hash => new stored blob

That is intentional for the current partial day, which can legitimately change across uploads.

## Blast radius constraints

Recommended server shape:

- dedicated Unix user, e.g. `ankidroid-ingest`
- locked shell (`/usr/sbin/nologin` or similar)
- `authorized_keys` entry with `restrict,command=...`
- landing directory owned by that user only
- ingest script writes only inside the fixed landing directory

## Reference files

- `authorized_keys.example` — example forced-command key options
- `ingest-ankidroid-daily-progress.py` — example stdin ingest script

## Minimal manual test recipe

1. Put the ingest script on a non-production server.
2. Create a dedicated ingest user and landing directory.
3. Install the public key with the forced-command options from `authorized_keys.example`.
4. In AnkiDroid developer options, set host / port / username / known_hosts / private key PEM.
5. Trigger **Upload current partial day JSON over SSH now**.
6. Verify the landing directory received exactly one JSON file and that repeated identical uploads are reported as duplicates.
