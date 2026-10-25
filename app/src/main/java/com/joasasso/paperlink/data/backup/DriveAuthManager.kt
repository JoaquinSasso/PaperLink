package com.joasasso.paperlink.data.backup

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.joasasso.paperlink.util.log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Proveedor de tokens OAuth2 para el respaldo en Google Drive.
 */
interface DriveTokenProvider {
    /**
     * Obtiene un access token vigente para `drive.appdata`.
     * @throws BackupException.AuthRequired si hace falta interacción del usuario (incluye `PendingIntent`)
     *   o si el permiso fue revocado.
     * @throws BackupException.NetworkError si falla la red al pedir el token.
     */
    suspend fun getAccessToken(): String

    /** Invalida un token rechazado por el servidor para forzar su renovación. */
    suspend fun invalidateToken(token: String)
}

/**
 * Autorización con Google Identity Services (`Identity.getAuthorizationClient`) pidiendo
 * únicamente el scope no sensible `drive.appdata` (carpeta oculta de la app).
 */
class DriveAuthManager(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DriveTokenProvider {

    override suspend fun getAccessToken(): String {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_APPDATA_SCOPE)))
            .build()
        val result = try {
            Identity.getAuthorizationClient(context).authorize(request).await()
        } catch (e: ApiException) {
            log("Drive authorization failed with status ${e.statusCode}", e)
            throw BackupException.AuthRequired(cause = e)
        } catch (e: Exception) {
            log("Drive authorization error", e)
            throw BackupException.NetworkError("Could not authorize with Google Drive", e)
        }
        return extractToken(result)
    }

    /**
     * Procesa el `Intent` devuelto por la pantalla de consentimiento de Google.
     */
    fun getTokenFromIntent(data: Intent?): String {
        if (data == null) throw BackupException.AuthRequired(message = "Authorization cancelled")
        val result = try {
            Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
        } catch (e: ApiException) {
            log("Drive consent result failed with status ${e.statusCode}", e)
            throw BackupException.AuthRequired(cause = e)
        }
        return extractToken(result)
    }

    override suspend fun invalidateToken(token: String) = withContext(ioDispatcher) {
        try {
            GoogleAuthUtil.clearToken(context, token)
        } catch (e: Exception) {
            log("Could not clear Google auth token", e)
        }
    }

    private fun extractToken(result: AuthorizationResult): String {
        if (result.hasResolution()) {
            throw BackupException.AuthRequired(
                resolution = result.pendingIntent,
                message = "User consent required for Google Drive",
            )
        }
        val token = result.accessToken
        if (token.isNullOrBlank()) {
            throw BackupException.AuthRequired(message = "Empty access token from Google Drive")
        }
        return token
    }

    companion object {
        const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        if (task.isCanceled) {
            cont.cancel()
        } else {
            val error = task.exception
            if (error != null) {
                cont.resumeWithException(error)
            } else {
                cont.resume(task.result)
            }
        }
    }
}
