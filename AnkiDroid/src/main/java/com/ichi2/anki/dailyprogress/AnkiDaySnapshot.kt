package com.ichi2.anki.dailyprogress

import org.json.JSONObject

data class AnkiDaySnapshot(
    val snapshotType: String,
    val windowKind: String,
    val generatedAtEpochMs: Long,
    val windowStartEpochMs: Long,
    val windowEndEpochMsExclusive: Long,
    val uniqueCardsStudied: Int,
    val answerEventsTotal: Int,
    val studyTimeMs: Long,
    val answerButtons: AnswerButtons,
    val reviewKinds: ReviewKinds,
    val activeDay: Boolean,
) {
    data class AnswerButtons(
        val again: Int,
        val hard: Int,
        val good: Int,
        val easy: Int,
    )

    data class ReviewKinds(
        val learn: Int,
        val review: Int,
        val relearn: Int,
        val filtered: Int,
    )

    fun toJson(): JSONObject =
        JSONObject().apply {
            put("snapshot_type", snapshotType)
            put("window_kind", windowKind)
            put("generated_at_epoch_ms", generatedAtEpochMs)
            put("window_start_epoch_ms", windowStartEpochMs)
            put("window_end_epoch_ms_exclusive", windowEndEpochMsExclusive)
            put("unique_cards_studied", uniqueCardsStudied)
            put("answer_events_total", answerEventsTotal)
            put("study_time_ms", studyTimeMs)
            put(
                "answer_buttons",
                JSONObject().apply {
                    put("again", answerButtons.again)
                    put("hard", answerButtons.hard)
                    put("good", answerButtons.good)
                    put("easy", answerButtons.easy)
                },
            )
            put(
                "review_kinds",
                JSONObject().apply {
                    put("learn", reviewKinds.learn)
                    put("review", reviewKinds.review)
                    put("relearn", reviewKinds.relearn)
                    put("filtered", reviewKinds.filtered)
                },
            )
            put("active_day", activeDay)
        }
}
