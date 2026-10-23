package com.joasasso.paperlink.util

import android.util.Log
import com.joasasso.paperlink.BuildConfig

private const val TAG = "PaperLink"

/**
 * Logger de la app. Solo escribe en builds de debug: en release no sale nada a logcat,
 * así que no hay riesgo de filtrar URIs ni datos del usuario.
 */
fun log(message: String, throwable: Throwable? = null) {
    if (!BuildConfig.DEBUG) return
    if (throwable == null) Log.d(TAG, message) else Log.w(TAG, message, throwable)
}
