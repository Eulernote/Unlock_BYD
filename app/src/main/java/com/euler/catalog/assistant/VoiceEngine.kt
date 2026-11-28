package com.euler.catalog.assistant

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
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
 * O reconhecimento (Vosk) já funciona nesta multimídia. O bloqueio é o microfone: o AudioRecord
 * inicia mas não entrega frames. Pela engenharia reversa do Electro/Sentry/Overdrive (que gravam
 * mic neste carro), a captura deles é um AudioRecord simples — a diferença está em CONFIG/ESTADO.
 * Então o abridor aqui: (1) tira o mute do mic, (2) lê o sample rate nativo do aparelho, e (3) varre
 * fontes × taxas × (mono/estéreo) lendo de verdade, e usa a 1ª combinação que entregar áudio.
 */
class VoiceEngine(
    private val ctx: Context,
    private val onWake: () -> Unit,
    private val onText: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val TAG = "VoiceEngine"
    private val OUT_RATE = 16000 // taxa que o Vosk espera

    @Volatile private var running = false
    private var thread: Thread? = null

    private var model: Model? = null
    @Volatile private var libReady = false

    private val WAKE = listOf("hey up", "ei up", "reup", "re up", "rê up", " up ")

    class MicPick(val rec: AudioRecord, val sampleRate: Int, val channels: Int, val source: Int) {
        fun desc() = "fonte=$source taxa=$sampleRate canais=$channels"
    }

    private fun ensureModel() {
        if (!libReady) {
            CrashLog.stage(ctx, "voz: carregando a lib nativa (Vosk)")
            LibVosk.setLogLevel(LogLevel.WARNINGS)
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

    /** Converte um frame lido (mono ou estéreo interleaved) para mono 16 kHz. */
    private fun paraMono16k(buf: ShortArray, n: Int, channels: Int, sr: Int): ShortArray {
        // 1) estéreo -> mono (média dos pares)
        val mono: ShortArray; val mN: Int
        if (channels >= 2) {
            mN = n / 2
            mono = ShortArray(mN)
            for (i in 0 until mN) mono[i] = ((buf[2 * i] + buf[2 * i + 1]) / 2).toShort()
        } else { mono = buf; mN = n }
        // 2) reamostra para 16 kHz (vizinho mais próximo)
        if (sr == OUT_RATE) return mono.copyOf(mN)
        val outN = (mN.toLong() * OUT_RATE / sr).toInt()
        if (outN <= 0) return ShortArray(0)
        val out = ShortArray(outN)
        val step = sr.toDouble() / OUT_RATE
        var pos = 0.0
        for (i in 0 until outN) { out[i] = mono[pos.toInt().coerceIn(0, mN - 1)]; pos += step }
        return out
    }

    private fun rmsDe(buf: ShortArray, n: Int, channels: Int): Double {
        var soma = 0.0; var c = 0
        var i = 0
        while (i < n) { val v = buf[i] / 32768.0; soma += v * v; c++; i += channels }
        return if (c > 0) sqrt(soma / c) else 0.0
    }

    /**
     * Abre o microfone de verdade: unmute + rate nativo + varredura fonte×taxa×canais lendo áudio real.
     */
    private fun openLiveMic(): MicPick? {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        var mutedAntes = "?"
        try {
            if (am != null) {
                mutedAntes = am.isMicrophoneMute.toString()
                am.isMicrophoneMute = false // tira o mute (suspeito nº1 de "inicia mas vem 0")
            }
        } catch (_: Throwable) {}
        val nativo = try { am?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0 } catch (_: Throwable) { 0 }
        CrashLog.stage(ctx, "mic: unmute (antes=$mutedAntes), rateNativo=$nativo")

        val taxas = LinkedHashSet<Int>().apply { if (nativo > 0) add(nativo); add(48000); add(16000); add(44100) }.toList()
        // (config, numCanais): estéreo primeiro — mic de carro costuma ser array
        val canais = listOf(AudioFormat.CHANNEL_IN_STEREO to 2, AudioFormat.CHANNEL_IN_MONO to 1)
        val fontes = listOf(6, 1, 7, 5, 0) // VOICE_RECOGNITION, MIC, VOICE_COMMUNICATION, CAMCORDER, DEFAULT
        var silencioInfo: String? = null // combinação que deu frames mas sem áudio (mic mudo)

        for ((cfg, cnt) in canais) for (sr in taxas) for (src in fontes) {
            val minBuf = AudioRecord.getMinBufferSize(sr, cfg, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) continue
            val ar = try {
                AudioRecord(src, sr, cfg, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 4, sr * cnt))
            } catch (_: Throwable) { continue }
            if (ar.state != AudioRecord.STATE_INITIALIZED) { try { ar.release() } catch (_: Throwable) {}; continue }
            var frames = 0; var pico = 0.0
            try {
                ar.startRecording()
                val buf = ShortArray(minBuf)
                val t0 = System.currentTimeMillis()
                while (System.currentTimeMillis() - t0 < 500) {
                    val n = ar.read(buf, 0, buf.size)
                    if (n > 0) { frames++; val r = rmsDe(buf, n, cnt); if (r > pico) pico = r }
                }
            } catch (_: Throwable) {}
            if (pico > 0.0008) {
                CrashLog.stage(ctx, "mic: ATIVO ${MicPick(ar, sr, cnt, src).desc()} pico=${"%.4f".format(pico)}")
                return MicPick(ar, sr, cnt, src)
            }
            if (frames > 0 && silencioInfo == null) silencioInfo = "fonte=$src taxa=$sr canais=$cnt (frames mas silencio)"
            try { ar.stop(); ar.release() } catch (_: Throwable) {}
        }
        CrashLog.stage(ctx, "mic: nenhuma combinacao entregou audio. silencio=${silencioInfo ?: "nenhum frame em nada"}")
        return null
    }

    private fun textoDe(json: String): String =
        try { JSONObject(json).optString("text").trim() } catch (_: Throwable) { "" }

    /**
     * Testa a captura via MediaRecorder (passa pelo audioserver do sistema, que tem privilégio de
     * áudio próprio) — caminho diferente do AudioRecord direto, que às vezes funciona onde o direto
     * não entrega frame. Mede a amplitude: se > 0, esse caminho capta e dá pra usar.
     */
    private fun testeMediaRecorder(): String {
        val fontes = listOf(6 to "VOICE_RECOGNITION", 1 to "MIC", 5 to "CAMCORDER")
        val f = java.io.File(ctx.cacheDir, "mictest.m4a")
        for ((src, nome) in fontes) {
            val mr = MediaRecorder()
            try {
                mr.setAudioSource(src)
                mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                mr.setAudioSamplingRate(16000)
                mr.setOutputFile(f.absolutePath)
                mr.prepare(); mr.start()
                var pico = 0
                val t0 = System.currentTimeMillis()
                while (System.currentTimeMillis() - t0 < 2500) {
                    Thread.sleep(150)
                    val a = try { mr.maxAmplitude } catch (_: Throwable) { 0 }
                    if (a > pico) pico = a
                }
                try { mr.stop() } catch (_: Throwable) {}
                val tam = f.length()
                CrashLog.stage(ctx, "MediaRecorder $nome: ampMax=$pico arq=${tam}B")
                if (pico > 0) return "MediaRecorder CAPTA via $nome (amp=$pico, arq=${tam}B) — da pra usar esse caminho!"
            } catch (e: Throwable) {
                CrashLog.stage(ctx, "MediaRecorder $nome falhou: ${e.message}")
            } finally { try { mr.release() } catch (_: Throwable) {} }
        }
        return "MediaRecorder tambem nao captou (amp 0 em todas as fontes)."
    }

    fun captureOnce() {
        Thread({
            CrashLog.stage(ctx, "voz: iniciando (push-to-talk)")
            try { ensureModel() } catch (e: Throwable) {
                onError("Erro ao iniciar a voz: ${e.message}"); CrashLog.write(ctx, "voz-init", e); return@Thread
            }
            val rec = try { Recognizer(model, OUT_RATE.toFloat()) } catch (e: Throwable) {
                onError("Erro ao criar o reconhecedor: ${e.message}"); return@Thread
            }
            val pick = try { openLiveMic() } catch (e: Throwable) {
                rec.close(); onError("Erro ao abrir o microfone: ${e.message}"); return@Thread
            }
            if (pick == null) {
                rec.close()
                CrashLog.stage(ctx, "voz: AudioRecord nao captou; testando MediaRecorder")
                val mr = testeMediaRecorder()
                onError("AudioRecord nao captou. $mr")
                return@Thread
            }
            val audio = pick.rec
            try {
                onWake()
                CrashLog.stage(ctx, "voz: gravando (fale agora) [${pick.desc()}]")
                val buf = ShortArray(maxOf(pick.sampleRate / 10 * pick.channels, 640))
                var falou = false
                var pico = 0.0
                val inicio = System.currentTimeMillis()
                var ultimaFala = inicio
                while (System.currentTimeMillis() - inicio < 8000) {
                    val n = audio.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    val rms = rmsDe(buf, n, pick.channels)
                    if (rms > pico) pico = rms
                    val a16 = paraMono16k(buf, n, pick.channels, pick.sampleRate)
                    if (a16.isNotEmpty()) rec.acceptWaveForm(a16, a16.size)
                    val agora = System.currentTimeMillis()
                    if (rms > 0.006) { falou = true; ultimaFala = agora }
                    else if (falou && agora - ultimaFala >= 800) break
                    else if (!falou && agora - inicio > 5000) break
                }
                val nivel = "%.4f".format(pico)
                CrashLog.stage(ctx, "voz: captura fim (nivelMax=$nivel falou=$falou ${pick.desc()})")
                val texto = textoDe(rec.finalResult)
                CrashLog.stage(ctx, "voz: reconhecido = \"$texto\"")
                when {
                    texto.isNotBlank() -> onText(texto)
                    pico < 0.004 -> onError("Mic abriu (${pick.desc()}) mas veio quase mudo (nivel $nivel).")
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
            onError("Erro ao iniciar a voz: ${e.message}"); return
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
        val rec = try { Recognizer(model, OUT_RATE.toFloat()) } catch (e: Throwable) {
            onError("Erro ao criar o reconhecedor: ${e.message}"); return
        }
        val pick = try { openLiveMic() } catch (e: Throwable) {
            rec.close(); onError("Erro ao abrir o microfone: ${e.message}"); return
        }
        if (pick == null) { rec.close(); onError("Mic nao entrega audio (modo ambiente)."); return }
        val audio = pick.rec
        val buf = ShortArray(maxOf(pick.sampleRate / 10 * pick.channels, 640))
        try {
            CrashLog.stage(ctx, "voz: ambiente ligado (diga Hey Up) [${pick.desc()}]")
            while (running) {
                val n = audio.read(buf, 0, buf.size)
                if (n <= 0) continue
                val a16 = paraMono16k(buf, n, pick.channels, pick.sampleRate)
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
