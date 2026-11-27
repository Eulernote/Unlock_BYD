package com.euler.catalog.assistant

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import kotlin.math.sqrt

/**
 * Motor de voz offline do "Hey Up", sobre o Vosk (Kaldi).
 *
 * Trocamos o sherpa-onnx pelo Vosk porque o onnxruntime crashava ao criar qualquer sessão
 * nesta multimídia BYD (a lib carregava, mas criar a sessão derrubava o app). O Vosk tem motor
 * nativo próprio, feito pra hardware fraco/antigo.
 *
 *  - "Falar agora" (push-to-talk): grava até o silêncio (detecção por energia) e transcreve.
 *  - "Ativar voz" (ambiente): reconhece contínuo e dispara quando ouve a palavra de ativação.
 */
class VoiceEngine(
    private val ctx: Context,
    private val onWake: () -> Unit,
    private val onText: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val TAG = "VoiceEngine"
    private val SAMPLE_RATE = 16000
    private val FRAME = 1600 // 100 ms

    @Volatile private var running = false
    private var thread: Thread? = null

    private var model: Model? = null
    @Volatile private var libReady = false

    private val WAKE = listOf("hey up", "ei up", "reup", "re up", "rê up", " up ")

    private fun ensureModel() {
        if (!libReady) {
            CrashLog.stage(ctx, "voz: carregando a lib nativa (Vosk)")
            LibVosk.setLogLevel(LogLevel.WARNINGS) // força o load do libvosk.so
            libReady = true
            CrashLog.stage(ctx, "voz: lib nativa OK")
        }
        if (model == null) {
            if (!VoiceModels.isReady(ctx)) VoiceModels.download(ctx) {}
            CrashLog.stage(ctx, "voz: carregando modelo (Vosk)")
            model = Model(VoiceModels.modelDir(ctx).absolutePath)
            CrashLog.stage(ctx, "voz: modelo carregado OK")
        }
    }

    private fun newAudioRecord(): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, SAMPLE_RATE))
    }

    private fun textoDe(json: String): String =
        try { JSONObject(json).optString("text").trim() } catch (_: Throwable) { "" }

    /**
     * "Falar agora": grava até o silêncio (detecção por energia) e transcreve com o Vosk.
     */
    fun captureOnce() {
        Thread({
            CrashLog.stage(ctx, "voz: iniciando (push-to-talk)")
            try { ensureModel() } catch (e: Throwable) {
                onError("Erro ao iniciar a voz: ${e.message}")
                CrashLog.write(ctx, "voz-init", e); return@Thread
            }
            val rec = try { Recognizer(model, SAMPLE_RATE.toFloat()) } catch (e: Throwable) {
                onError("Erro ao criar o reconhecedor: ${e.message}")
                CrashLog.write(ctx, "voz-recognizer", e); return@Thread
            }
            val audio = try { newAudioRecord() } catch (e: Throwable) {
                rec.close(); onError("Sem acesso ao microfone: ${e.message}"); return@Thread
            }
            try {
                audio.startRecording()
                onWake()
                CrashLog.stage(ctx, "voz: gravando (fale agora)")
                val buf = ShortArray(FRAME)
                var falou = false
                var silencioMs = 0
                val inicio = System.currentTimeMillis()
                while (System.currentTimeMillis() - inicio < 8000) {
                    val n = audio.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var soma = 0.0
                    for (i in 0 until n) { val v = buf[i] / 32768.0f; soma += (v * v).toDouble() }
                    val rms = sqrt(soma / n)
                    rec.acceptWaveForm(buf, n)
                    if (rms > 0.018) { falou = true; silencioMs = 0 }
                    else if (falou) { silencioMs += 100; if (silencioMs >= 700) break }
                    else if (System.currentTimeMillis() - inicio > 4000) break
                }
                if (falou) {
                    CrashLog.stage(ctx, "voz: reconhecendo a fala")
                    val texto = textoDe(rec.finalResult)
                    CrashLog.stage(ctx, "voz: reconhecido = \"$texto\"")
                    if (texto.isNotBlank()) onText(texto) else onError("Nao entendi. Tente de novo.")
                } else onError("Nao ouvi nada. Fale logo apos tocar o botao.")
            } catch (e: Throwable) {
                onError("Erro na captura: ${e.message}"); CrashLog.write(ctx, "voz-captura", e)
            } finally {
                try { audio.stop(); audio.release() } catch (_: Throwable) {}
                try { rec.close() } catch (_: Throwable) {}
            }
        }, "hey-up-ptt").start()
    }

    fun start() {
        if (running) return
        try { ensureModel() } catch (e: Throwable) {
            onError("Erro ao iniciar a voz: ${e.message}"); CrashLog.write(ctx, "voz-init", e); return
        }
        running = true
        thread = Thread({ loop() }, "hey-up-voice").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
    }

    private fun loop() {
        val rec = try { Recognizer(model, SAMPLE_RATE.toFloat()) } catch (e: Throwable) {
            onError("Erro ao criar o reconhecedor: ${e.message}"); return
        }
        val audio = try { newAudioRecord() } catch (e: Throwable) {
            rec.close(); onError("Sem acesso ao microfone: ${e.message}"); return
        }
        val buf = ShortArray(FRAME)
        try {
            audio.startRecording()
            CrashLog.stage(ctx, "voz: ambiente ligado (diga Hey Up)")
            while (running) {
                val n = audio.read(buf, 0, buf.size)
                if (n <= 0) continue
                if (rec.acceptWaveForm(buf, n)) {
                    val texto = textoDe(rec.result).lowercase()
                    if (texto.isBlank()) continue
                    val gatilho = WAKE.firstOrNull { texto.contains(it.trim()) }
                    if (gatilho != null) {
                        onWake()
                        val comando = texto.substringAfter(gatilho.trim()).trim()
                        if (comando.isNotBlank()) onText(comando)
                    }
                }
            }
        } catch (e: Throwable) {
            onError("Erro na captura: ${e.message}")
        } finally {
            try { audio.stop(); audio.release() } catch (_: Throwable) {}
            try { rec.close() } catch (_: Throwable) {}
        }
    }
}
