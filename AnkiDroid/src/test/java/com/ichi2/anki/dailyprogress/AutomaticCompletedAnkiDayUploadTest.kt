/*
 *  Copyright (c) 2026 Pavel Mironchik
 *
 *  This program is free software; you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation; either version 3 of the License, or (at your option) any later
 *  version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY
 *  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *  PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.ichi2.anki.dailyprogress

import org.junit.Test
import kotlin.test.assertEquals

class AutomaticCompletedAnkiDayUploadTest {
    private val dayDurationMs = 24L * 60L * 60L * 1000L

    @Test
    fun `planner looks back a bounded number of completed days when state is missing`() {
        val windows =
            CompletedAnkiDayUploadPlanner.planPendingCompletedWindows(
                lastHandledWindowEndExclusive = null,
                currentDayStartEpochMs = 10L * dayDurationMs,
                initialLookbackDays = 3,
            )

        assertEquals(
            listOf(7L, 8L, 9L).map { dayIndex ->
                CompletedAnkiDayWindow(
                    startEpochMs = dayIndex * dayDurationMs,
                    endEpochMsExclusive = (dayIndex + 1) * dayDurationMs,
                )
            },
            windows,
        )
    }

    @Test
    fun `planner resumes from the first unhandled completed day`() {
        val windows =
            CompletedAnkiDayUploadPlanner.planPendingCompletedWindows(
                lastHandledWindowEndExclusive = 8L * dayDurationMs,
                currentDayStartEpochMs = 10L * dayDurationMs,
            )

        assertEquals(
            listOf(8L, 9L).map { dayIndex ->
                CompletedAnkiDayWindow(
                    startEpochMs = dayIndex * dayDurationMs,
                    endEpochMsExclusive = (dayIndex + 1) * dayDurationMs,
                )
            },
            windows,
        )
    }

    @Test
    fun `planner returns no work when handled state is already caught up`() {
        val windows =
            CompletedAnkiDayUploadPlanner.planPendingCompletedWindows(
                lastHandledWindowEndExclusive = 10L * dayDurationMs,
                currentDayStartEpochMs = 10L * dayDurationMs,
            )

        assertEquals(emptyList(), windows)
    }

    @Test
    fun `automatic completed upload snapshot is stable for retries`() {
        val snapshot =
            AnkiDaySnapshot(
                snapshotType = "completed",
                windowKind = "last_non_empty_completed_anki_day",
                generatedAtEpochMs = 123L,
                windowStartEpochMs = 1_000L,
                windowEndEpochMsExclusive = 2_000L,
                uniqueCardsStudied = 5,
                answerEventsTotal = 8,
                studyTimeMs = 9_000L,
                answerButtons =
                    AnkiDaySnapshot.AnswerButtons(
                        again = 1,
                        hard = 2,
                        good = 3,
                        easy = 2,
                    ),
                reviewKinds =
                    AnkiDaySnapshot.ReviewKinds(
                        learn = 1,
                        review = 6,
                        relearn = 1,
                        filtered = 0,
                    ),
                activeDay = true,
            )

        val stabilized = snapshot.stabilizedForAutomaticCompletedUpload()

        assertEquals("completed", stabilized.snapshotType)
        assertEquals(AUTO_COMPLETED_ANKI_DAY_WINDOW_KIND, stabilized.windowKind)
        assertEquals(2_000L, stabilized.generatedAtEpochMs)
        assertEquals(snapshot.copy(windowKind = AUTO_COMPLETED_ANKI_DAY_WINDOW_KIND, generatedAtEpochMs = 2_000L), stabilized)
    }
}
