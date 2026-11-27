package com.euler.catalog.assistant

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Voz de resposta do assistente.
 *
 * Nesta primeira versão usa a TTS nativa do Android em português (não baixa nada,
 * já fala de cara). A voz Razo (Piper, offline) entra numa próxima versão, trocando
 * só esta classe por uma que roda o modelo Razo pelo sherpa-onnx OfflineTts.
 */
class Speaker(ctx: Context) {

    private var ready = false
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(ctx.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                try { tts?.language = Locale("pt", "BR") } catch (_: Throwable) {}
                ready = true
            }
        }
    }

    fun speak(text: String) {
        if (!ready || text.isBlank()) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "heyup")
    }

    fun shutdown() {
        try { tts?.stop(); tts?.shutdown() } catch (_: Throwable) {}
    }
}
