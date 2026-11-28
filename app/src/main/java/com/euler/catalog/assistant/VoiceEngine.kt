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

    class MicPick(val rec: AudioRecord, val sampleRate: Int, val source: Int)

    /**
     * Abre o microfone testando várias FONTES e TAXAS até achar uma que entregue áudio de verdade.
     * Em multimídia BYD a fonte/ taxa certa varia (às vezes só MIC, às vezes só 48 kHz), e as demais
     * vêm zeradas. Detecta pelo "chão de ruído": um stream vivo sempre tem algum sample != 0.
     */
    private fun openLiveMic(): MicPick? {
        val fontes = listOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.DEFAULT
        )
        val taxas = listOf(16000, 48000, 44100)
        var abriuAlgum = false
        for (sr in taxas) for (src in fontes) {
            val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) continue
            val ar = try {
                AudioRecord(src, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, sr))
            } catch (_: Throwable) { continue }
            if (ar.state != AudioRecord.STATE_INITIALIZED) { try { ar.release() } catch (_: Throwable) {}; continue }
            abriuAlgum = true
            var vivo = false
            try {
                ar.startRecording()
                val probe = ShortArray(sr / 5)
                val t0 = System.currentTimeMillis()
                while (System.currentTimeMillis() - t0 < 350 && !vivo) {
                    val n = ar.read(probe, 0, probe.size)
                    if (n > 0) for (i in 0 until n) if (probe[i].toInt() != 0) { vivo = true; break }
                }
            } catch (_: Throwable) {}
            if (vivo) {
                CrashLog.stage(ctx, "mic: ATIVO fonte=$src taxa=$sr")
                return MicPick(ar, sr, src)
            }
            try { ar.stop(); ar.release() } catch (_: Throwable) {}
        }
        CrashLog.stage(ctx, "mic: nenhuma fonte entregou audio (abriu=$abriuAlgum)")
        return null
    }

    /** Reamostra para 16 kHz (vizinho mais próximo; suficiente p/ reconhecimento). */
    private fun to16k(src: ShortArray, n: Int, sr: Int): ShortArray {
        if (sr == SAMPLE_RATE) return src.copyOf(n)
        val outN = (n.toLong() * SAMPLE_RATE / sr).toInt()
        if (outN <= 0) return ShortArray(0)
        val out = ShortArray(outN)
        val step = sr.toDouble() / SAMPLE_RATE
        var pos = 0.0
        for (i in 0 until outN) { out[i] = src[pos.toInt().coerceIn(0, n - 1)]; pos += step }
        return out
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
            val pick = try { openLiveMic() } catch (e: Throwable) {
                rec.close(); onError("Erro ao abrir o microfone: ${e.message}"); return@Thread
            }
            if (pick == null) {
                rec.close()
                onError("Mic mudo em todas as fontes (nivel 0). Esta ocupado/bloqueado pelo sistema.")
                return@Thread
            }
            val audio = pick.rec
            try {
                onWake()
                CrashLog.stage(ctx, "voz: gravando (fale agora) [fonte=${pick.source} taxa=${pick.sampleRate}]")
                val buf = ShortArray(maxOf(pick.sampleRate / 10, 320))
                var falou = false
                var pico = 0.0
                val inicio = System.currentTimeMillis()
                var ultimaFala = inicio
                while (System.currentTimeMillis() - inicio < 8000) {
                    val n = audio.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var soma = 0.0
                    for (i in 0 until n) { val v = buf[i] / 32768.0f; soma += (v * v).toDouble() }
                    val rms = sqrt(soma / n)
                    if (rms > pico) pico = rms
                    val a16 = to16k(buf, n, pick.sampleRate)
                    if (a16.isNotEmpty()) rec.acceptWaveForm(a16, a16.size)
                    val agora = System.currentTimeMillis()
                    if (rms > 0.006) { falou = true; ultimaFala = agora }
                    else if (falou && agora - ultimaFala >= 800) break
                    else if (!falou && agora - inicio > 5000) break
                }
                val nivel = String.format("%.4f", pico)
                CrashLog.stage(ctx, "voz: captura fim (nivelMax=$nivel falou=$falou fonte=${pick.source} taxa=${pick.sampleRate})")
                val texto = textoDe(rec.finalResult)
                CrashLog.stage(ctx, "voz: reconhecido = \"$texto\"")
                when {
                    texto.isNotBlank() -> onText(texto)
                    pico < 0.004 -> onError("Mic abriu (fonte ${pick.source}/${pick.sampleRate}Hz) mas veio quase mudo (nivel $nivel).")
                    else -> onError("Nao entendi (nivel $nivel). Fale mais claro apos tocar.")
                }
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
        val pick = try { openLiveMic() } catch (e: Throwable) {
            rec.close(); onError("Erro ao abrir o microfone: ${e.message}"); return
        }
        if (pick == null) {
            rec.close(); onError("Mic mudo em todas as fontes (nivel 0). Esta ocupado/bloqueado pelo sistema."); return
        }
        val audio = pick.rec
        val buf = ShortArray(maxOf(pick.sampleRate / 10, 320))
        try {
            CrashLog.stage(ctx, "voz: ambiente ligado (diga Hey Up) [fonte=${pick.source} taxa=${pick.sampleRate}]")
            while (running) {
                val n = audio.read(buf, 0, buf.size)
                if (n <= 0) continue
                val a16 = to16k(buf, n, pick.sampleRate)
                if (a16.isNotEmpty() && rec.acceptWaveForm(a16, a16.size)) {
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
