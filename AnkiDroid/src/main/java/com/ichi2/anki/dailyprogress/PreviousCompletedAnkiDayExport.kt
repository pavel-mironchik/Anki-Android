package com.ichi2.anki.dailyprogress

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import java.io.File

interface PreviousCompletedAnkiDayExportSink {
    fun export(snapshot: PreviousCompletedAnkiDaySnapshot): File
}

class PreviousCompletedAnkiDayJsonFileSink(
    private val context: Context,
) : PreviousCompletedAnkiDayExportSink {
    override fun export(snapshot: PreviousCompletedAnkiDaySnapshot): File {
        val outputDirectory =
            requireNotNull(context.getExternalFilesDir(null)) {
                "External files directory unavailable"
            }.resolve("daily-progress")
                .apply { mkdirs() }

        return outputDirectory
            .resolve(
                "previous-completed-anki-day-${snapshot.windowStartEpochMs}-${snapshot.windowEndEpochMsExclusive}.json",
            ).apply {
                writeText(snapshot.toJson().toString(2))
            }
    }
}

object PreviousCompletedAnkiDayExport {
    fun shareIntent(
        context: Context,
        file: File,
    ): Intent {
        val authority = "${context.packageName}.apkgfileprovider"
        val uri: Uri = FileProvider.getUriForFile(context, authority, file)
        val sendIntent =
            ShareCompat
                .IntentBuilder(context)
                .setType("application/json")
                .setStream(uri)
                .setSubject(file.name)
                .intent
                .apply {
                    clipData = ClipData.newUri(context.contentResolver, file.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
        return Intent.createChooser(sendIntent, file.name)
    }
}
