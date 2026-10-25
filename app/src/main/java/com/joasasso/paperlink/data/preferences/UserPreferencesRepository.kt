package com.joasasso.paperlink.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.joasasso.paperlink.util.log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private const val PREFERENCE_NAME = "paperlink_preferences"
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = PREFERENCE_NAME)

/**
 * Estado persistido del respaldo automático en Google Drive y de los avisos en la Home.
 */
data class BackupSettings(
    val driveEnabled: Boolean = false,
    val chargingOnly: Boolean = false,
    val lastBackupAt: Long? = null,
    val lastBackupSizeBytes: Long? = null,
    val driveNeedsReauth: Boolean = false,
    val suggestionDismissed: Boolean = false,
) {
    /** True si el respaldo está activo y el último respaldo exitoso tiene más de 7 días (garantía 13). */
    fun isStale(now: Long): Boolean =
        driveEnabled && lastBackupAt != null && now - lastBackupAt > STALE_THRESHOLD_MS

    /** True si hay que mostrar la sugerencia de activar el respaldo (garantía 15). */
    fun shouldSuggestBackup(linkCount: Int): Boolean =
        !driveEnabled && !suggestionDismissed && linkCount >= SUGGEST_MIN_LINKS

    companion object {
        const val STALE_THRESHOLD_MS = 7L * 24 * 60 * 60 * 1000
        const val SUGGEST_MIN_LINKS = 5
    }
}

class UserPreferencesRepository(
    private val dataStore: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.dataStore)

    private object PreferencesKeys {
        val IS_FIRST_LAUNCH = booleanPreferencesKey("is_first_launch")
        val DRIVE_BACKUP_ENABLED = booleanPreferencesKey("drive_backup_enabled")
        val DRIVE_CHARGING_ONLY = booleanPreferencesKey("drive_charging_only")
        val LAST_BACKUP_AT = longPreferencesKey("last_backup_at")
        val LAST_BACKUP_SIZE_BYTES = longPreferencesKey("last_backup_size_bytes")
        val DRIVE_NEEDS_REAUTH = booleanPreferencesKey("drive_needs_reauth")
        val BACKUP_SUGGESTION_DISMISSED = booleanPreferencesKey("backup_suggestion_dismissed")
    }

    private val preferencesFlow: Flow<Preferences> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                log("Error reading preferences", exception)
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }

    val isFirstLaunch: Flow<Boolean> = preferencesFlow.map { preferences ->
        preferences[PreferencesKeys.IS_FIRST_LAUNCH] ?: true
    }

    val backupSettings: Flow<BackupSettings> = preferencesFlow.map { preferences ->
        BackupSettings(
            driveEnabled = preferences[PreferencesKeys.DRIVE_BACKUP_ENABLED] ?: false,
            chargingOnly = preferences[PreferencesKeys.DRIVE_CHARGING_ONLY] ?: false,
            lastBackupAt = preferences[PreferencesKeys.LAST_BACKUP_AT],
            lastBackupSizeBytes = preferences[PreferencesKeys.LAST_BACKUP_SIZE_BYTES],
            driveNeedsReauth = preferences[PreferencesKeys.DRIVE_NEEDS_REAUTH] ?: false,
            suggestionDismissed = preferences[PreferencesKeys.BACKUP_SUGGESTION_DISMISSED] ?: false,
        )
    }

    suspend fun setFirstLaunchCompleted() {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.IS_FIRST_LAUNCH] = false
        }
    }

    suspend fun setDriveBackupEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DRIVE_BACKUP_ENABLED] = enabled
            if (enabled) {
                preferences[PreferencesKeys. DRIVE_NEEDS_REAUTH] = false
                preferences[PreferencesKeys.BACKUP_SUGGESTION_DISMISSED] = true
            }
        }
    }

    suspend fun setDriveChargingOnly(chargingOnly: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DRIVE_CHARGING_ONLY] = chargingOnly
        }
    }

    suspend fun recordDriveBackupSuccess(timestamp: Long, totalSizeBytes: Long) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DRIVE_BACKUP_ENABLED] = true
            preferences[PreferencesKeys.LAST_BACKUP_AT] = timestamp
            preferences[PreferencesKeys.LAST_BACKUP_SIZE_BYTES] = totalSizeBytes
            preferences[PreferencesKeys.DRIVE_NEEDS_REAUTH] = false
            preferences[PreferencesKeys.BACKUP_SUGGESTION_DISMISSED] = true
        }
    }

    suspend fun recordDriveAuthRevoked() {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DRIVE_NEEDS_REAUTH] = true
        }
    }

    suspend fun disconnectDrive() {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DRIVE_BACKUP_ENABLED] = false
            preferences[PreferencesKeys.DRIVE_NEEDS_REAUTH] = false
        }
    }

    suspend fun dismissBackupSuggestion() {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.BACKUP_SUGGESTION_DISMISSED] = true
        }
    }
}
