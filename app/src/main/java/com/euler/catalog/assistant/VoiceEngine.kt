package com.euler.catalog.assistant

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlin.math.sqrt

/**
 * Motor de voz offline do "Hey Up", sobre o sherpa-onnx.
 *
 * Dois caminhos:
 *  - "Falar agora" (push-to-talk): grava com detecção de fala POR ENERGIA (sem o VAD onnx,
 *    que estava crashando ao criar a sessão nesta multimídia) e transcreve com o Whisper.
 *  - "Ativar voz" (ambiente): usa o VAD silero + palavra de ativação (KWS). É best-effort;
 *    se o VAD onnx crashar neste carro, o push-to-talk continua funcionando.
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

    private var kws: KeywordSpotter? = null
    private var vad: Vad? = null
    private var asr: OfflineRecognizer? = null

    @Volatile private var libLoaded = false
    @Volatile private var asrReady = false

    private fun loadLib() {
        if (libLoaded) return
        CrashLog.stage(ctx, "voz: carregando a lib nativa (dlopen)")
        try { System.loadLibrary("sherpa-onnx-jni") } catch (e: Throwable) {
            CrashLog.stage(ctx, "voz: FALHA ao carregar a lib (Java): ${e.javaClass.simpleName}"); throw e
        }
        libLoaded = true
        CrashLog.stage(ctx, "voz: lib carregada OK")
    }

    /** Só o reconhecimento (Whisper) — usado pelo push-to-talk, sem VAD onnx. */
    private fun ensureAsr() {
        if (asrReady) return
        loadLib()
        CrashLog.stage(ctx, "voz: criando sessao ASR (Whisper)")
        asr = OfflineRecognizer(
            assetManager = null,
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = VoiceModels.path(ctx, "asr/encoder.onnx"),
                        decoder = VoiceModels.path(ctx, "asr/decoder.onnx"),
                        language = "pt",
                        task = "transcribe"
                    ),
                    tokens = VoiceModels.path(ctx, "asr/tokens.txt"),
                    numThreads = 1,
                    modelType = "whisper"
                )
            )
        )
        asrReady = true
        CrashLog.stage(ctx, "voz: ASR (Whisper) pronto")
    }

    /** VAD + KWS para o modo ambiente (pode crashar nesta ROM; é best-effort). */
    private fun ensureAmbient() {
        ensureAsr()
        val feat = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80)
        if (vad == null) {
            CrashLog.stage(ctx, "voz: criando sessao VAD (onnx)")
            vad = Vad(
                assetManager = null,
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = VoiceModels.path(ctx, "vad/silero_vad.onnx"),
                        threshold = 0.5f, minSilenceDuration = 0.4f,
                        minSpeechDuration = 0.25f, maxSpeechDuration = 8.0f, windowSize = 512
                    ),
                    sampleRate = SAMPLE_RATE, numThreads = 1, provider = "cpu"
                )
            )
        }
        if (kws == null) {
            CrashLog.stage(ctx, "voz: criando sessao KWS")
            kws = try {
                KeywordSpotter(
                    assetManager = null,
                    config = KeywordSpotterConfig(
                        featConfig = feat,
                        modelConfig = OnlineModelConfig(
                            transducer = OnlineTransducerModelConfig(
                                encoder = VoiceModels.path(ctx, "kws/encoder.onnx"),
                                decoder = VoiceModels.path(ctx, "kws/decoder.onnx"),
                                joiner = VoiceModels.path(ctx, "kws/joiner.onnx")
                            ),
                            tokens = VoiceModels.path(ctx, "kws/tokens.txt"),
                            numThreads = 1, provider = "cpu", modelType = "zipformer2"
                        ),
                        keywordsFile = VoiceModels.path(ctx, "kws/keywords.txt"),
                        keywordsScore = 1.5f, keywordsThreshold = 0.2f, numTrailingBlanks = 1
                    )
                )
            } catch (e: Throwable) { Log.w(TAG, "KWS indisponivel: ${e.message}"); null }
        }
        CrashLog.stage(ctx, "voz: modo ambiente pronto")
    }

    private fun newAudioRecord(): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, SAMPLE_RATE))
    }

    private fun recognize(samples: FloatArray): String {
        val r = asr ?: return ""
        return try {
            val s = r.createStream()
            s.acceptWaveform(samples, SAMPLE_RATE)
            r.decode(s)
            r.getResult(s).text.trim()
        } catch (e: Throwable) { Log.w(TAG, "recognize: ${e.message}"); "" }
    }

    /**
     * "Falar agora": grava até o silêncio (detecção por energia, sem VAD onnx) e transcreve.
     */
    fun captureOnce() {
        Thread({
            CrashLog.stage(ctx, "voz: iniciando (push-to-talk)")
            try { ensureAsr() } catch (e: Throwable) { onError("Erro ao iniciar a voz: ${e.message}"); return@Thread }
            val rec = try { newAudioRecord() } catch (e: Throwable) {
                onError("Sem acesso ao microfone: ${e.message}"); return@Thread
            }
            try {
                rec.startRecording()
                onWake()
                CrashLog.stage(ctx, "voz: gravando (fale agora)")
                val buf = ShortArray(FRAME)
                val coletado = ArrayList<Float>(SAMPLE_RATE * 8)
                var falou = false
                var silencioMs = 0
                val inicio = System.currentTimeMillis()
                while (System.currentTimeMillis() - inicio < 8000) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    var soma = 0.0
                    val frame = FloatArray(n)
                    for (i in 0 until n) { val v = buf[i] / 32768.0f; frame[i] = v; soma += (v * v).toDouble() }
                    val rms = sqrt(soma / n)
                    if (rms > 0.018) { // fala
                        falou = true; silencioMs = 0
                        for (v in frame) coletado.add(v)
                    } else if (falou) {
                        silencioMs += 100
                        for (v in frame) coletado.add(v)
                        if (silencioMs >= 700) break // fim da fala
                    } else if (System.currentTimeMillis() - inicio > 4000) {
                        break // ninguém falou
                    }
                }
                if (falou && coletado.size > SAMPLE_RATE / 2) {
                    CrashLog.stage(ctx, "voz: reconhecendo a fala")
                    val arr = FloatArray(coletado.size) { coletado[it] }
                    val texto = recognize(arr)
                    CrashLog.stage(ctx, "voz: reconhecido = \"$texto\"")
                    if (texto.isNotBlank()) onText(texto) else onError("Nao entendi. Tente de novo.")
                } else onError("Nao ouvi nada. Fale logo apos tocar o botao.")
            } catch (e: Throwable) {
                onError("Erro na captura: ${e.message}")
            } finally {
                try { rec.stop(); rec.release() } catch (_: Throwable) {}
            }
        }, "hey-up-ptt").start()
    }

    fun start() {
        if (running) return
        try { ensureAmbient() } catch (e: Throwable) { onError("Erro ao iniciar a voz: ${e.message}"); return }
        running = true
        thread = Thread({ loop() }, "hey-up-voice").apply { start() }
    }

    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
    }

    private fun loop() {
        val rec = try { newAudioRecord() } catch (e: Throwable) {
            onError("Sem acesso ao microfone: ${e.message}"); return
        }
        val k = kws
        if (k == null) {
            onError("Palavra de ativacao indisponivel. Use \"Falar agora\".")
            try { rec.release() } catch (_: Throwable) {}; return
        }
        val v = vad
        val buf = ShortArray(FRAME)
        val kStream = k.createStream("")
        try {
            rec.startRecording()
            var capturing = false
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                val samples = FloatArray(n) { buf[it] / 32768.0f }
                if (!capturing) {
                    kStream.acceptWaveform(samples, SAMPLE_RATE)
                    while (k.isReady(kStream)) k.decode(kStream)
                    if (k.getResult(kStream).keyword.isNotEmpty()) {
                        k.reset(kStream); capturing = true; onWake()
                    }
                } else if (v != null) {
                    v.acceptWaveform(samples)
                    while (!v.empty()) {
                        val seg = v.front()
                        val text = recognize(seg.samples)
                        v.pop(); capturing = false
                        if (text.isNotBlank()) onText(text)
                    }
                } else capturing = false
            }
        } catch (e: Throwable) {
            onError("Erro na captura: ${e.message}")
        } finally {
            try { rec.stop(); rec.release() } catch (_: Throwable) {}
        }
    }
}
