package com.joasasso.paperlink.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
    /** Cuándo se activó el respaldo automático (para avisar si nunca terminó bien). */
    val driveEnabledAt: Long? = null,
    /**
     * Id en Drive del último `manifest.json` que este teléfono subió o restauró. Si el de Drive es
     * otro, Drive tiene contenido que este teléfono no conoce y no se lo puede pisar.
     */
    val syncedManifestId: String? = null,
    /** Drive tiene un respaldo que todavía no se fusionó en este teléfono (respaldo frenado). */
    val remotePending: Boolean = false,
) {
    /**
     * True si el respaldo está activo y no hubo uno exitoso en más de 7 días (garantía 13).
     * Si nunca terminó ninguno, cuenta desde que se activó.
     */
    fun isStale(now: Long): Boolean {
        if (!driveEnabled) return false
        val reference = lastBackupAt ?: driveEnabledAt ?: return false
        return now - reference > STALE_THRESHOLD_MS
    }

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
        val DRIVE_ENABLED_AT = longPreferencesKey("drive_enabled_at")
        val DRIVE_SYNCED_MANIFEST_ID = stringPreferencesKey("drive_synced_manifest_id")
        val DRIVE_REMOTE_PENDING = booleanPreferencesKey("drive_remote_pending")
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
            driveEnabledAt = preferences[PreferencesKeys.DRIVE_ENABLED_AT],
            syncedManifestId = preferences[PreferencesKeys.DRIVE_SYNCED_MANIFEST_ID],
            remotePending = preferences[PreferencesKeys.DRIVE_REMOTE_PENDING] ?: false,
        )
    }

    suspend fun setFirstLaunchCompleted() {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.IS_FIRST_LAUNCH] = false
        }
    }

    suspend fun setDriveBackupEnabled(enabled: Boolean, now: Long = System.currentTimeMillis()) {
        dataStore.edit { preferences ->
            val wasEnabled = preferences[PreferencesKeys.DRIVE_BACKUP_ENABLED] ?: false
            preferences[PreferencesKeys.DRIVE_BACKUP_ENABLED] = enabled
            if (enabled) {
                if (!wasEnabled) preferences[PreferencesKeys.DRIVE_ENABLED_AT] = now
                preferences[PreferencesKeys.DRIVE_NEEDS_REAUTH] = false
                preferences[PreferencesKeys.BACKUP_SUGGESTION_DISMISSED] = true
            }
        }
    }

    suspend fun setDriveChargingOnly(chargingOnly: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DRIVE_CHARGING_ONLY] = chargingOnly
        }
    }

    /**
     * Registra un respaldo exitoso. No toca `driveEnabled`: si el usuario lo apagó mientras corría,
     * sigue apagado.
     */
    suspend fun recordDriveBackupSuccess(timestamp: Long, totalSizeBytes: Long, manifestId: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DRIVE_SYNCED_MANIFEST_ID] = manifestId
            preferences[PreferencesKeys.DRIVE_REMOTE_PENDING] = false
            preferences[PreferencesKeys.LAST_BACKUP_AT] = timestamp
            preferences[PreferencesKeys.LAST_BACKUP_SIZE_BYTES] = totalSizeBytes
            preferences[PreferencesKeys.DRIVE_NEEDS_REAUTH] = false
            preferences[PreferencesKeys.BACKUP_SUGGESTION_DISMISSED] = true
        }
    }

    /** El respaldo de Drive [manifestId] ya está fusionado en este teléfono. */
    suspend fun recordDriveRestore(manifestId: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DRIVE_SYNCED_MANIFEST_ID] = manifestId
            preferences[PreferencesKeys.DRIVE_REMOTE_PENDING] = false
        }
    }

    /** Se frenó un respaldo porque Drive tiene contenido que este teléfono no tiene. */
    suspend fun recordRemotePending() {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DRIVE_REMOTE_PENDING] = true
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
            preferences[PreferencesKeys.DRIVE_REMOTE_PENDING] = false
        }
    }

    suspend fun dismissBackupSuggestion() {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.BACKUP_SUGGESTION_DISMISSED] = true
        }
    }
}
