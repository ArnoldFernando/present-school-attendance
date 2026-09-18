package com.attendancefr.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "attendance_fr_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store = context.settingsStore

    val confidenceThreshold: Flow<Float> = store.data.map { prefs ->
        prefs[KEY_THRESHOLD] ?: DEFAULT_THRESHOLD
    }

    val lastSelectedClass: Flow<String> = store.data.map { prefs ->
        prefs[KEY_LAST_CLASS].orEmpty()
    }

    suspend fun setConfidenceThreshold(value: Float) {
        val clamped = value.coerceIn(0.30f, 0.95f)
        store.edit { it[KEY_THRESHOLD] = clamped }
    }

    suspend fun setLastSelectedClass(name: String) {
        store.edit { it[KEY_LAST_CLASS] = name }
    }

    companion object {
        /** Default cosine-similarity threshold. See ACCURACY_NOTES.md. */
        const val DEFAULT_THRESHOLD = 0.60f
        private val KEY_THRESHOLD = floatPreferencesKey("confidence_threshold")
        private val KEY_LAST_CLASS = stringPreferencesKey("last_selected_class")
    }
}
