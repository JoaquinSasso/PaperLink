package com.joasasso.paperlink

import android.app.Application
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder
import com.joasasso.paperlink.di.AppContainer
import com.joasasso.paperlink.di.DefaultAppContainer
import com.joasasso.paperlink.util.PdfDecoder
import com.joasasso.paperlink.worker.PaperLinkWorkerFactory

/**
 * Application class de PaperLink.
 * ... (resto de docs)
 */
class PaperLinkApp : Application(), SingletonImageLoader.Factory, Configuration.Provider {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(
                PaperLinkWorkerFactory(
                    container.paperLinkRepository,
                    container.generateCodeUseCase
                )
            )
            .build()

    override fun newImageLoader(context: android.content.Context): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(VideoFrameDecoder.Factory())
                add(PdfDecoder.Factory())
            }
            .build()
    }
}
