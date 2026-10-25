package com.joasasso.paperlink

import android.app.Application
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.video.VideoFrameDecoder
import com.joasasso.paperlink.data.backup.WorkManagerBackupScheduler
import com.joasasso.paperlink.di.AppContainer
import com.joasasso.paperlink.util.PdfDecoder
import com.joasasso.paperlink.util.log
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Application class de PaperLink.
 *
 * Mantiene una instancia única de [AppContainer] accesible desde toda la app; los ViewModels
 * la obtienen desde su Factory (`APPLICATION_KEY`).
 *
 * `container` es `lateinit` y no `by lazy` adrede: queremos que se inicialice
 * en `onCreate` y falle ruidosamente si algo se accede antes (lo cual sería un bug).
 */
class PaperLinkApp : Application(), SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Purga de borrados vencidos, barrido de huérfanos y verificación del respaldo diario.
        container.applicationScope.launch {
            container.linkRepository.runMaintenance()
            val backupSettings = container.userPreferencesRepository.backupSettings.first()
            if (backupSettings.driveEnabled) {
                WorkManagerBackupScheduler(this@PaperLinkApp).ensureScheduled(backupSettings.chargingOnly)
            }
        }
        publishCaptureShortcut()
    }

    /** Atajo del ícono de la app ("Sacar foto") que abre la cámara directamente. */
    private fun publishCaptureShortcut() {
        val shortcut = ShortcutInfoCompat.Builder(this, "capture")
            .setShortLabel(getString(R.string.shortcut_capture_short))
            .setLongLabel(getString(R.string.shortcut_capture_long))
            .setIcon(IconCompat.createWithResource(this, R.drawable.ic_shortcut_camera))
            .setIntent(Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_CAPTURE))
            .build()
        try {
            ShortcutManagerCompat.pushDynamicShortcut(this, shortcut)
        } catch (e: RuntimeException) {
            // El launcher puede limitar la frecuencia; el atajo ya publicado sigue funcionando.
            log("Could not publish shortcut", e)
        }
    }

    override fun newImageLoader(context: android.content.Context): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory())
                add(VideoFrameDecoder.Factory())
                add(PdfDecoder.Factory())
            }
            .build()
    }
}
