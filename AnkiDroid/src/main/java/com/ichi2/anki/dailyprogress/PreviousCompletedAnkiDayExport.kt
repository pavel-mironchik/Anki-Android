package com.ichi2.anki.dailyprogress

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import java.io.File

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
