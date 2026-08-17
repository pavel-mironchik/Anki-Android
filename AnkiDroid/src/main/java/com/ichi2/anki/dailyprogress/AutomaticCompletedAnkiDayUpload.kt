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

import android.content.Context
import androidx.core.content.edit
import com.ichi2.anki.AnkiDroidApp
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.R
import com.ichi2.anki.preferences.sharedPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.time.Duration.Companion.days

internal const val AUTO_COMPLETED_ANKI_DAY_WINDOW_KIND = "completed_anki_day_auto_upload"
private const val DEFAULT_INITIAL_COMPLETED_ANKI_DAY_LOOKBACK_DAYS = 7
private const val FAILED_UPLOAD_RETRY_COOLDOWN_MS = 15 * 60 * 1000L
private val COMPLETED_ANKI_DAY_DURATION_MS = 1.days.inWholeMilliseconds

internal data class CompletedAnkiDayWindow(
    val startEpochMs: Long,
    val endEpochMsExclusive: Long,
)

internal object CompletedAnkiDayUploadPlanner {
    fun planPendingCompletedWindows(
        lastHandledWindowEndExclusive: Long?,
        currentDayStartEpochMs: Long,
        initialLookbackDays: Int = DEFAULT_INITIAL_COMPLETED_ANKI_DAY_LOOKBACK_DAYS,
    ): List<CompletedAnkiDayWindow> {
        if (currentDayStartEpochMs <= 0L) {
            return emptyList()
        }

        val firstWindowStartEpochMs =
            when {
                lastHandledWindowEndExclusive == null -> {
                    (currentDayStartEpochMs - (initialLookbackDays.toLong() * COMPLETED_ANKI_DAY_DURATION_MS)).coerceAtLeast(0L)
                }
                lastHandledWindowEndExclusive >= currentDayStartEpochMs -> return emptyList()
                else -> lastHandledWindowEndExclusive
            }

        return generateSequence(firstWindowStartEpochMs) { windowStartEpochMs ->
            windowStartEpochMs + COMPLETED_ANKI_DAY_DURATION_MS
        }.takeWhile { windowStartEpochMs -> windowStartEpochMs < currentDayStartEpochMs }
            .map { windowStartEpochMs ->
                CompletedAnkiDayWindow(
                    startEpochMs = windowStartEpochMs,
                    endEpochMsExclusive = windowStartEpochMs + COMPLETED_ANKI_DAY_DURATION_MS,
                )
            }.toList()
    }
}

internal fun AnkiDaySnapshot.stabilizedForAutomaticCompletedUpload(): AnkiDaySnapshot =
    copy(
        snapshotType = "completed",
        windowKind = AUTO_COMPLETED_ANKI_DAY_WINDOW_KIND,
        generatedAtEpochMs = windowEndEpochMsExclusive,
    )

private interface CompletedAnkiDayUploadProgressStore {
    fun lastHandledWindowEndExclusive(): Long?

    fun markWindowHandled(windowEndEpochMsExclusive: Long)
}

private class SharedPreferencesCompletedAnkiDayUploadProgressStore(
    context: Context,
) : CompletedAnkiDayUploadProgressStore {
    private val appContext = context.applicationContext
    private val key = appContext.getString(R.string.pref_auto_completed_anki_day_upload_last_handled_window_end_key)

    override fun lastHandledWindowEndExclusive(): Long? {
        val sharedPreferences = appContext.sharedPrefs()
        if (!sharedPreferences.contains(key)) {
            return null
        }
        return sharedPreferences.getLong(key, 0L)
    }

    override fun markWindowHandled(windowEndEpochMsExclusive: Long) {
        appContext.sharedPrefs().edit {
            putLong(key, windowEndEpochMsExclusive)
        }
    }
}

object AutomaticCompletedAnkiDayUpload {
    private val runMutex = Mutex()

    @Volatile
    private var nextRetryNotBeforeEpochMs = 0L

    internal fun trigger(
        context: Context,
        reason: String,
        onFinished: ((AutomaticCompletedAnkiDayUploadOutcome) -> Unit)? = null,
    ) {
        val now = System.currentTimeMillis()
        if (now < nextRetryNotBeforeEpochMs) {
            if (onFinished != null) {
                AnkiDroidApp.applicationScope.launch(Dispatchers.Main.immediate) {
                    onFinished(
                        AutomaticCompletedAnkiDayUploadOutcome.Skipped.RetryCooldown(
                            retryAfterMs = nextRetryNotBeforeEpochMs - now,
                        ),
                    )
                }
            }
            return
        }

        val appContext = context.applicationContext
        AnkiDroidApp.applicationScope.launch(Dispatchers.IO) {
            if (!runMutex.tryLock()) {
                Timber.v("Automatic completed Anki-day upload already running; skipping trigger=%s", reason)
                if (onFinished != null) {
                    withContext(Dispatchers.Main.immediate) {
                        onFinished(AutomaticCompletedAnkiDayUploadOutcome.Skipped.AlreadyRunning)
                    }
                }
                return@launch
            }

            var outcome: AutomaticCompletedAnkiDayUploadOutcome = AutomaticCompletedAnkiDayUploadOutcome.Skipped.NothingPending
            try {
                outcome = AutomaticCompletedAnkiDayUploader(appContext).run(reason)
            } catch (throwable: Throwable) {
                Timber.w(throwable, "Automatic completed Anki-day upload failed (trigger=%s)", reason)
                outcome =
                    AutomaticCompletedAnkiDayUploadOutcome.FailedRetryable(
                        throwable.localizedMessage ?: throwable.javaClass.simpleName,
                    )
            } finally {
                applyRetryCooldown(outcome)
                runMutex.unlock()
            }

            Timber.i("Automatic completed Anki-day upload finished trigger=%s outcome=%s", reason, outcome)

            if (onFinished != null) {
                withContext(Dispatchers.Main.immediate) {
                    onFinished(outcome)
                }
            }
        }
    }

    private fun applyRetryCooldown(outcome: AutomaticCompletedAnkiDayUploadOutcome) {
        when (outcome) {
            is AutomaticCompletedAnkiDayUploadOutcome.FailedRetryable -> {
                nextRetryNotBeforeEpochMs = System.currentTimeMillis() + FAILED_UPLOAD_RETRY_COOLDOWN_MS
            }
            is AutomaticCompletedAnkiDayUploadOutcome.Skipped,
            is AutomaticCompletedAnkiDayUploadOutcome.Progressed,
            -> {
                if (nextRetryNotBeforeEpochMs <= System.currentTimeMillis()) {
                    nextRetryNotBeforeEpochMs = 0L
                }
            }
        }
    }
}

internal sealed interface AutomaticCompletedAnkiDayUploadOutcome {
    sealed interface Skipped : AutomaticCompletedAnkiDayUploadOutcome {
        data object AlreadyRunning : Skipped

        data class RetryCooldown(
            val retryAfterMs: Long,
        ) : Skipped

        data object NotConfigured : Skipped

        data class InvalidConfiguration(
            val userFacingMessage: String,
        ) : Skipped

        data object NothingPending : Skipped

        data object NoActiveStudyToUpload : Skipped
    }

    data class Progressed(
        val uploadedWindowCount: Int,
    ) : AutomaticCompletedAnkiDayUploadOutcome

    data class FailedRetryable(
        val userFacingMessage: String,
    ) : AutomaticCompletedAnkiDayUploadOutcome
}

private class AutomaticCompletedAnkiDayUploader(
    private val context: Context,
    private val snapshotBuilder: AnkiDaySnapshotBuilder = AnkiDaySnapshotBuilder(),
    private val payloadRenderer: AnkiDaySnapshotPayloadRenderer = AnkiDaySnapshotPayloadRenderer(),
    private val progressStore: CompletedAnkiDayUploadProgressStore = SharedPreferencesCompletedAnkiDayUploadProgressStore(context),
) {
    suspend fun run(reason: String): AutomaticCompletedAnkiDayUploadOutcome {
        val configProvider = ForcedCommandSshUploadConfigProvider(context)
        if (!configProvider.isConfigured()) {
            return AutomaticCompletedAnkiDayUploadOutcome.Skipped.NotConfigured
        }

        val config =
            try {
                configProvider.loadOrThrow()
            } catch (exception: ForcedCommandSshUploadException) {
                Timber.i("Automatic completed Anki-day upload skipped due to invalid SSH config: %s", exception.message)
                return AutomaticCompletedAnkiDayUploadOutcome.Skipped.InvalidConfiguration(
                    exception.message ?: "Invalid forced-command SSH configuration",
                )
            }
        val uploadSink = ForcedCommandSshAnkiDayUploadSink(config)
        val currentDayStartEpochMs = withCol { snapshotBuilder.currentAnkiDayStartEpochMs(this) }
        val pendingWindows =
            CompletedAnkiDayUploadPlanner.planPendingCompletedWindows(
                lastHandledWindowEndExclusive = progressStore.lastHandledWindowEndExclusive(),
                currentDayStartEpochMs = currentDayStartEpochMs,
            )
        if (pendingWindows.isEmpty()) {
            return AutomaticCompletedAnkiDayUploadOutcome.Skipped.NothingPending
        }

        Timber.i(
            "Automatic completed Anki-day upload check trigger=%s pending=%d currentDayStart=%d",
            reason,
            pendingWindows.size,
            currentDayStartEpochMs,
        )

        var progressed = false
        var uploadedWindowCount = 0

        for (window in pendingWindows) {
            val snapshot =
                withCol {
                    snapshotBuilder.buildCompletedWindow(
                        collection = this,
                        windowStartEpochMs = window.startEpochMs,
                        windowKind = AUTO_COMPLETED_ANKI_DAY_WINDOW_KIND,
                    )
                }.stabilizedForAutomaticCompletedUpload()

            if (!snapshot.activeDay) {
                progressStore.markWindowHandled(window.endEpochMsExclusive)
                progressed = true
                continue
            }

            val payload = payloadRenderer.render(snapshot)
            try {
                val result = uploadSink.send(payload)
                progressStore.markWindowHandled(window.endEpochMsExclusive)
                progressed = true
                uploadedWindowCount += 1
                Timber.i(
                    "Automatic completed Anki-day upload sent %s trigger=%s receipt=%s",
                    result.uploadedFileName,
                    reason,
                    result.remoteReceipt,
                )
            } catch (exception: ForcedCommandSshUploadException) {
                Timber.w(
                    exception,
                    "Automatic completed Anki-day upload failed for window %d..%d (trigger=%s)",
                    window.startEpochMs,
                    window.endEpochMsExclusive,
                    reason,
                )
                return AutomaticCompletedAnkiDayUploadOutcome.FailedRetryable(
                    exception.message ?: "Forced-command SSH upload failed",
                )
            }
        }

        return if (uploadedWindowCount > 0) {
            AutomaticCompletedAnkiDayUploadOutcome.Progressed(uploadedWindowCount = uploadedWindowCount)
        } else if (progressed) {
            AutomaticCompletedAnkiDayUploadOutcome.Skipped.NoActiveStudyToUpload
        } else {
            AutomaticCompletedAnkiDayUploadOutcome.Skipped.NothingPending
        }
    }
}
