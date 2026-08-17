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

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import com.google.android.material.snackbar.Snackbar
import com.ichi2.anki.Channel
import com.ichi2.anki.DeckPicker
import com.ichi2.anki.R
import com.ichi2.anki.notifications.NotificationId
import com.ichi2.utils.Permissions

internal fun AutomaticCompletedAnkiDayUploadOutcome.userFacingMessage(context: Context): String? =
    when (this) {
        is AutomaticCompletedAnkiDayUploadOutcome.Progressed -> {
            if (uploadedWindowCount <= 0) {
                null
            } else {
                context.resources.getQuantityString(
                    R.plurals.completed_anki_day_upload_success,
                    uploadedWindowCount,
                    uploadedWindowCount,
                )
            }
        }
        is AutomaticCompletedAnkiDayUploadOutcome.FailedRetryable ->
            context.getString(R.string.completed_anki_day_upload_failed, userFacingMessage)
        is AutomaticCompletedAnkiDayUploadOutcome.Skipped.AlreadyRunning ->
            context.getString(R.string.completed_anki_day_upload_skipped_already_running)
        is AutomaticCompletedAnkiDayUploadOutcome.Skipped.RetryCooldown -> {
            val retryAfterMinutes = ((retryAfterMs + 60_000L - 1L) / 60_000L).coerceAtLeast(1L)
            context.getString(R.string.completed_anki_day_upload_skipped_retry_cooldown, retryAfterMinutes)
        }
        is AutomaticCompletedAnkiDayUploadOutcome.Skipped.NotConfigured ->
            context.getString(R.string.completed_anki_day_upload_skipped_not_configured)
        is AutomaticCompletedAnkiDayUploadOutcome.Skipped.InvalidConfiguration ->
            context.getString(R.string.completed_anki_day_upload_skipped_invalid_config, userFacingMessage)
        is AutomaticCompletedAnkiDayUploadOutcome.Skipped.NothingPending ->
            context.getString(R.string.completed_anki_day_upload_skipped_nothing_pending)
        is AutomaticCompletedAnkiDayUploadOutcome.Skipped.NoActiveStudyToUpload ->
            context.getString(R.string.completed_anki_day_upload_skipped_no_active_study)
    }

internal fun AutomaticCompletedAnkiDayUploadOutcome.foregroundSnackbarDuration(): Int =
    when (this) {
        is AutomaticCompletedAnkiDayUploadOutcome.Progressed -> Snackbar.LENGTH_SHORT
        is AutomaticCompletedAnkiDayUploadOutcome.FailedRetryable,
        is AutomaticCompletedAnkiDayUploadOutcome.Skipped,
        -> Snackbar.LENGTH_LONG
    }

private fun AutomaticCompletedAnkiDayUploadOutcome.notificationTitle(context: Context): String? =
    when (this) {
        is AutomaticCompletedAnkiDayUploadOutcome.Progressed ->
            if (uploadedWindowCount > 0) {
                context.getString(R.string.completed_anki_day_upload_notification_title)
            } else {
                null
            }
        is AutomaticCompletedAnkiDayUploadOutcome.FailedRetryable ->
            context.getString(R.string.completed_anki_day_upload_notification_failed_title)
        is AutomaticCompletedAnkiDayUploadOutcome.Skipped -> null
    }

internal fun showAutomaticCompletedAnkiDayUploadNotification(
    context: Context,
    outcome: AutomaticCompletedAnkiDayUploadOutcome,
) {
    if (!Permissions.canPostNotifications(context)) {
        return
    }

    val title = outcome.notificationTitle(context) ?: return
    val message = outcome.userFacingMessage(context) ?: return

    val resultIntent =
        Intent(context, DeckPicker::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
    val pendingIntent =
        PendingIntentCompat.getActivity(
            context,
            NotificationId.SYNC_COMPLETED_ANKI_DAY_UPLOAD,
            resultIntent,
            PendingIntent.FLAG_UPDATE_CURRENT,
            false,
        )

    val notification =
        NotificationCompat
            .Builder(context, Channel.SYNC.id)
            .setSmallIcon(R.drawable.ic_star_notify)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

    NotificationManagerCompat.from(context).notify(NotificationId.SYNC_COMPLETED_ANKI_DAY_UPLOAD, notification)
}
