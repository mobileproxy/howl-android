package io.nekohasekai.sfa.health

import io.nekohasekai.sfa.database.Settings

/** Single serialized writer. One SQLite REPLACE is atomic; no throwing listener runs after commit. */
object SelectionPreferenceRuntime {
    val store = ProfileSelectionPreferences(object : AtomicPreferenceStorage {
        override fun read(): String? = Settings.selectionPreferences
        override fun writeAtomically(value: String) = Settings.writeSelectionPreferences(value)
    })
}
