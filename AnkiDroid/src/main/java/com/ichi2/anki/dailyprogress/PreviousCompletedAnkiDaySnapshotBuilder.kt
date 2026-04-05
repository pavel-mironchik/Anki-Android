package com.ichi2.anki.dailyprogress

import com.ichi2.anki.libanki.Collection

class PreviousCompletedAnkiDaySnapshotBuilder {
    fun build(collection: Collection): PreviousCompletedAnkiDaySnapshot = AnkiDaySnapshotBuilder().buildPreviousCompleted(collection)
}
