package com.joasasso.paperlink.data.preferences

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private const val PREFERENCE_NAME = "paperlink_preferences"
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = PREFERENCE_NAME)

class UserPreferencesRepository(private val context: Context) {

    private object PreferencesKeys {
        val DISMISSED_URIS = stringSetPreferencesKey("dismissed_photo_uris")
        val IS_FIRST_LAUNCH = booleanPreferencesKey("is_first_launch")
    }

    val isFirstLaunch: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Log.e("UserPreferences", "Error reading preferences.", exception)
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            preferences[PreferencesKeys.IS_FIRST_LAUNCH] ?: true
        }

    suspend fun setFirstLaunchCompleted() {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.IS_FIRST_LAUNCH] = false
        }
    }

    val dismissedUris: Flow<Set<String>> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Log.e("UserPreferences", "Error reading preferences.", exception)
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val set = preferences[PreferencesKeys.DISMISSED_URIS] ?: emptySet()
            Log.d("PaperLinkBanner", "[DATASTORE READ] Loaded ${set.size} URIs from disk")
            set
        }

    suspend fun saveDismissedUri(uri: String) {
        Log.d("PaperLinkBanner", "[DATASTORE WRITE] Saving URI to disk: $uri")
        context.dataStore.edit { preferences ->
            val currentUris = preferences[PreferencesKeys.DISMISSED_URIS] ?: emptySet()
            preferences[PreferencesKeys.DISMISSED_URIS] = currentUris + uri
        }
    }
}
