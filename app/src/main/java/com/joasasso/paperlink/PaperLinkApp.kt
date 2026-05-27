package com.joasasso.paperlink

import android.app.Application
import com.joasasso.paperlink.di.AppContainer
import com.joasasso.paperlink.di.DefaultAppContainer

/**
 * Application class de PaperLink.
 *
 * Mantiene una instancia única de [AppContainer] accesible desde toda la app.
 * Los ViewModels la obtienen así (Fase 5):
 *
 *     val app = LocalContext.current.applicationContext as PaperLinkApp
 *     val repo = app.container.paperLinkRepository
 *
 * `container` es `lateinit` y no `by lazy` adrede: queremos que se inicialice
 * en `onCreate` y falle ruidosamente si algo se accede antes (lo cual sería un bug).
 */
class PaperLinkApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
    }
}
