package com.ichi2.anki.dailyprogress

import com.ichi2.anki.libanki.Collection
import kotlin.time.Duration.Companion.days

class AnkiDaySnapshotBuilder {
    private val dayDurationMs = 1.days.inWholeMilliseconds

    fun currentAnkiDayStartEpochMs(collection: Collection): Long = currentDayCutoffEpochMs(collection) - dayDurationMs

    fun buildCurrentPartial(collection: Collection): AnkiDaySnapshot {
        val currentDayCutoffEpochMs = currentDayCutoffEpochMs(collection)
        return buildWindowSnapshot(
            collection = collection,
            snapshotType = "partial",
            windowKind = "current_partial_anki_day",
            windowStartEpochMs = currentDayCutoffEpochMs - dayDurationMs,
            windowEndEpochMsExclusive = System.currentTimeMillis(),
        )
    }

    fun buildPreviousCompleted(collection: Collection): AnkiDaySnapshot {
        val currentDayCutoffEpochMs = currentDayCutoffEpochMs(collection)
        return buildCompletedWindow(
            collection = collection,
            windowKind = "previous_completed_anki_day",
            windowStartEpochMs = currentDayCutoffEpochMs - (2 * dayDurationMs),
        )
    }

    fun buildLastNonEmptyCompleted(collection: Collection): AnkiDaySnapshot? {
        val currentDayStartEpochMs = currentAnkiDayStartEpochMs(collection)
        val latestReviewEpochMs =
            collection.db.queryLongScalar(
                """
                SELECT MAX(id)
                FROM revlog
                WHERE id < ?
                  AND ease BETWEEN 1 AND 4
                """.trimIndent(),
                currentDayStartEpochMs,
            )

        if (latestReviewEpochMs <= 0L) {
            return null
        }

        val completedDaysBack = (currentDayStartEpochMs - latestReviewEpochMs - 1) / dayDurationMs
        val windowStartEpochMs = currentDayStartEpochMs - ((completedDaysBack + 1) * dayDurationMs)
        return buildCompletedWindow(
            collection = collection,
            windowKind = "last_non_empty_completed_anki_day",
            windowStartEpochMs = windowStartEpochMs,
        )
    }

    fun buildCompletedWindow(
        collection: Collection,
        windowStartEpochMs: Long,
        windowKind: String,
    ): AnkiDaySnapshot =
        buildWindowSnapshot(
            collection = collection,
            snapshotType = "completed",
            windowKind = windowKind,
            windowStartEpochMs = windowStartEpochMs,
            windowEndEpochMsExclusive = windowStartEpochMs + dayDurationMs,
        )

    private fun currentDayCutoffEpochMs(collection: Collection): Long = collection.sched.dayCutoff * 1000L

    private fun buildWindowSnapshot(
        collection: Collection,
        snapshotType: String,
        windowKind: String,
        windowStartEpochMs: Long,
        windowEndEpochMsExclusive: Long,
    ): AnkiDaySnapshot {
        val query =
            """
            SELECT
                COUNT(DISTINCT cid) AS unique_cards_studied,
                COUNT(*) AS answer_events_total,
                COALESCE(SUM(CASE WHEN time > 0 THEN time ELSE 0 END), 0) AS study_time_ms,
                COUNT(CASE WHEN ease = 1 THEN 1 END) AS answer_again,
                COUNT(CASE WHEN ease = 2 THEN 1 END) AS answer_hard,
                COUNT(CASE WHEN ease = 3 THEN 1 END) AS answer_good,
                COUNT(CASE WHEN ease = 4 THEN 1 END) AS answer_easy,
                COUNT(CASE WHEN type = 0 THEN 1 END) AS review_kind_learn,
                COUNT(CASE WHEN type = 1 THEN 1 END) AS review_kind_review,
                COUNT(CASE WHEN type = 2 THEN 1 END) AS review_kind_relearn,
                COUNT(CASE WHEN type = 3 THEN 1 END) AS review_kind_filtered
            FROM revlog
            WHERE id >= ? AND id < ?
              AND ease BETWEEN 1 AND 4
            """.trimIndent()

        collection.db.query(query, windowStartEpochMs, windowEndEpochMsExclusive).use { cursor ->
            check(cursor.moveToFirst()) { "Expected aggregate row for Anki day snapshot" }
            val answerEventsTotal = cursor.getInt(1)
            return AnkiDaySnapshot(
                snapshotType = snapshotType,
                windowKind = windowKind,
                generatedAtEpochMs = System.currentTimeMillis(),
                windowStartEpochMs = windowStartEpochMs,
                windowEndEpochMsExclusive = windowEndEpochMsExclusive,
                uniqueCardsStudied = cursor.getInt(0),
                answerEventsTotal = answerEventsTotal,
                studyTimeMs = cursor.getLong(2),
                answerButtons =
                    AnkiDaySnapshot.AnswerButtons(
                        again = cursor.getInt(3),
                        hard = cursor.getInt(4),
                        good = cursor.getInt(5),
                        easy = cursor.getInt(6),
                    ),
                reviewKinds =
                    AnkiDaySnapshot.ReviewKinds(
                        learn = cursor.getInt(7),
                        review = cursor.getInt(8),
                        relearn = cursor.getInt(9),
                        filtered = cursor.getInt(10),
                    ),
                activeDay = answerEventsTotal > 0,
            )
        }
    }
}
