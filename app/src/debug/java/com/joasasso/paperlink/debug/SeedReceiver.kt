package com.joasasso.paperlink.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.joasasso.paperlink.PaperLinkApp
import com.joasasso.paperlink.data.repository.AttachmentSource
import com.joasasso.paperlink.util.log
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Solo en debug: carga o quita códigos de prueba para medir rendimiento.
 *
 *     adb shell am broadcast -a com.joasasso.paperlink.debug.SEED --ei count 200 -p com.joasasso.paperlink.debug
 *     adb shell am broadcast -a com.joasasso.paperlink.debug.UNSEED -p com.joasasso.paperlink.debug
 *
 * Los códigos de prueba llevan la etiqueta "[seed] N"; UNSEED borra solo esos.
 */
class SeedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as PaperLinkApp).container
        val repository = container.linkRepository
        val pending = goAsync()
        container.applicationScope.launch {
            try {
                when (intent.action) {
                    ACTION_SEED -> {
                        val count = intent.getIntExtra("count", 200)
                        repeat(count) { i ->
                            val source = if (i % 2 == 0) {
                                AttachmentSource.Note("Nota de prueba ${i + 1}")
                            } else {
                                AttachmentSource.WebUrl("https://example.com/prueba/${i + 1}")
                            }
                            val code = repository.createLink(listOf(source), label = "$SEED_LABEL ${i + 1}")
                            if (i == count - 1) log("Seed: last code $code")
                        }
                        log("Seed: created $count codes")
                    }
                    ACTION_UNSEED -> {
                        val seeded = repository.searchByLabel(SEED_LABEL).first()
                            .filter { it.link.label?.startsWith(SEED_LABEL) == true }
                        seeded.forEach { repository.delete(it.link.code) }
                        log("Seed: deleted ${seeded.size} codes")
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val ACTION_SEED = "com.joasasso.paperlink.debug.SEED"
        const val ACTION_UNSEED = "com.joasasso.paperlink.debug.UNSEED"
        const val SEED_LABEL = "[seed]"
    }
}
