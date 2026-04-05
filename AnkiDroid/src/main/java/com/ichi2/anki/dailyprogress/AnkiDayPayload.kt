package com.ichi2.anki.dailyprogress

import android.content.Context
import java.io.File

interface AnkiDayExportSink {
    fun export(snapshot: AnkiDaySnapshot): File
}

interface AnkiDayPayloadSink<T> {
    fun send(payload: AnkiDaySnapshotPayload): T
}

data class AnkiDaySnapshotPayload(
    val fileName: String,
    val jsonText: String,
) {
    val utf8Bytes: ByteArray
        get() = jsonText.toByteArray(Charsets.UTF_8)
}

class AnkiDaySnapshotPayloadRenderer {
    fun render(snapshot: AnkiDaySnapshot): AnkiDaySnapshotPayload =
        AnkiDaySnapshotPayload(
            fileName = "${snapshot.windowKind}-${snapshot.windowStartEpochMs}-${snapshot.windowEndEpochMsExclusive}.json",
            jsonText = snapshot.toJson().toString(2),
        )
}

class AnkiDayJsonFileSink(
    private val context: Context,
) : AnkiDayExportSink,
    AnkiDayPayloadSink<File> {
    private val renderer = AnkiDaySnapshotPayloadRenderer()

    override fun export(snapshot: AnkiDaySnapshot): File = send(renderer.render(snapshot))

    override fun send(payload: AnkiDaySnapshotPayload): File {
        val outputDirectory =
            requireNotNull(context.getExternalFilesDir(null)) {
                "External files directory unavailable"
            }.resolve("daily-progress")
                .apply { mkdirs() }

        return outputDirectory
            .resolve(payload.fileName)
            .apply {
                writeText(payload.jsonText)
            }
    }
}

class PreviousCompletedAnkiDayJsonFileSink(
    context: Context,
) : AnkiDayExportSink by AnkiDayJsonFileSink(context)
