package com.joasasso.paperlink

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.video.VideoFrameDecoder
import com.joasasso.paperlink.di.AppContainer
import com.joasasso.paperlink.util.PdfDecoder
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
        // Purga de borrados vencidos y barrido de huérfanos, en segundo plano.
        container.applicationScope.launch { container.linkRepository.runMaintenance() }
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
