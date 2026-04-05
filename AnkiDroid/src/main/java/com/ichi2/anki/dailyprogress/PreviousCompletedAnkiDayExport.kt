package com.ichi2.anki.dailyprogress

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import java.io.File

interface AnkiDayExportSink {
    fun export(snapshot: AnkiDaySnapshot): File
}

class AnkiDayJsonFileSink(
    private val context: Context,
) : AnkiDayExportSink {
    override fun export(snapshot: AnkiDaySnapshot): File {
        val outputDirectory =
            requireNotNull(context.getExternalFilesDir(null)) {
                "External files directory unavailable"
            }.resolve("daily-progress")
                .apply { mkdirs() }

        return outputDirectory
            .resolve(
                "${snapshot.windowKind}-${snapshot.windowStartEpochMs}-${snapshot.windowEndEpochMsExclusive}.json",
            ).apply {
                writeText(snapshot.toJson().toString(2))
            }
    }
}

class PreviousCompletedAnkiDayJsonFileSink(
    context: Context,
) : AnkiDayExportSink by AnkiDayJsonFileSink(context)

object AnkiDayExport {
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

object PreviousCompletedAnkiDayExport {
    fun shareIntent(
        context: Context,
        file: File,
    ): Intent = AnkiDayExport.shareIntent(context, file)
}
