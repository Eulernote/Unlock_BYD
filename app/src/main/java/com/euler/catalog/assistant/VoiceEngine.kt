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

/**
 * Motor de voz offline do "Hey Up", sobre o sherpa-onnx.
 *
 * Fluxo (padrão do sherpa):
 *   mic (16 kHz) -> KeywordSpotter escuta "Hey Up" -> ao detectar, entra em captura:
 *   VAD acha o trecho de fala -> Whisper (pt) transcreve -> devolve o texto pelo callback.
 *
 * Tudo offline, no carro. Os modelos vêm de VoiceModels (baixados do R2).
 * É best-effort: qualquer erro de init cai no onError e o serviço segue sem derrubar o app.
 */
class VoiceEngine(
    private val ctx: Context,
    private val onWake: () -> Unit,
    private val onText: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val TAG = "VoiceEngine"
    private val SAMPLE_RATE = 16000

    @Volatile private var running = false
    private var thread: Thread? = null

    private var kws: KeywordSpotter? = null
    private var vad: Vad? = null
    private var asr: OfflineRecognizer? = null

    @Volatile private var inited = false

    private fun ensureInit() {
        if (inited) return
        initModels()
        inited = true
    }

    fun start() {
        if (running) return
        try {
            ensureInit()
        } catch (e: Throwable) {
            onError("Erro ao iniciar a voz: ${e.message}")
            return
        }
        running = true
        thread = Thread({ loop() }, "hey-up-voice").apply { start() }
    }

    /**
     * "Falar agora" (push-to-talk): grava um comando SEM esperar a palavra de ativação,
     * transcreve e devolve o texto. Útil pra testar voz->comando sem depender do "Hey Up".
     * Só usar quando o modo ambiente (start) não estiver rodando.
     */
    fun captureOnce() {
        Thread({
            try { ensureInit() } catch (e: Throwable) { onError("Erro ao iniciar a voz: ${e.message}"); return@Thread }
            val rec = try { newAudioRecord() } catch (e: Throwable) {
                onError("Sem acesso ao microfone: ${e.message}"); return@Thread
            }
            val buf = ShortArray(1600)
            val v = vad ?: run { onError("VAD indisponivel"); return@Thread }
            try {
                rec.startRecording()
                onWake()
                val limite = System.currentTimeMillis() + 7000
                var texto = ""
                while (System.currentTimeMillis() < limite && texto.isBlank()) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    val samples = FloatArray(n) { buf[it] / 32768.0f }
                    v.acceptWaveform(samples)
                    while (!v.empty()) {
                        val seg = v.front()
                        texto = recognize(seg.samples)
                        v.pop()
                    }
                }
                if (texto.isNotBlank()) onText(texto) else onError("Nao ouvi nada. Tente de novo.")
            } catch (e: Throwable) {
                onError("Erro na captura: ${e.message}")
            } finally {
                try { rec.stop(); rec.release() } catch (_: Throwable) {}
            }
        }, "hey-up-ptt").start()
    }

    fun stop() {
        running = false
        thread?.join(1500)
        thread = null
        try { kws?.let { } } catch (_: Throwable) {}
    }

    private fun initModels() {
        val feat = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80)

        // Palavra de ativação ("Hey Up") — modelo KWS zipformer stream.
        kws = KeywordSpotter(
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
                    numThreads = 1,
                    provider = "cpu",
                    modelType = "zipformer2"
                ),
                keywordsFile = VoiceModels.path(ctx, "kws/keywords.txt"),
                keywordsScore = 1.5f,
                keywordsThreshold = 0.25f,
                numTrailingBlanks = 1
            )
        )

        // Detecção de fala (quando a pessoa começa/para de falar).
        vad = Vad(
            assetManager = null,
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = VoiceModels.path(ctx, "vad/silero_vad.onnx"),
                    threshold = 0.5f,
                    minSilenceDuration = 0.4f,
                    minSpeechDuration = 0.25f,
                    maxSpeechDuration = 8.0f,
                    windowSize = 512
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu"
            ),
            bufferSizeInSeconds = 60.0f
        )

        // Reconhecimento em português (Whisper multilíngue).
        asr = OfflineRecognizer(
            assetManager = null,
            config = OfflineRecognizerConfig(
                featConfig = feat,
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = VoiceModels.path(ctx, "asr/encoder.onnx"),
                        decoder = VoiceModels.path(ctx, "asr/decoder.onnx"),
                        language = "pt",
                        task = "transcribe"
                    ),
                    tokens = VoiceModels.path(ctx, "asr/tokens.txt"),
                    numThreads = 2,
                    modelType = "whisper"
                )
            )
        )
    }

    private fun newAudioRecord(): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        return AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, SAMPLE_RATE) // >= 1s
        )
    }

    private fun loop() {
        val rec = try { newAudioRecord() } catch (e: Throwable) {
            onError("Sem acesso ao microfone: ${e.message}"); return
        }
        val buf = ShortArray(1600) // 100ms
        val k = kws ?: return
        val kStream = k.createStream("")
        try {
            rec.startRecording()
            var capturing = false
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                val samples = FloatArray(n) { buf[it] / 32768.0f }

                if (!capturing) {
                    // escutando a palavra de ativação
                    kStream.acceptWaveform(samples, SAMPLE_RATE)
                    while (k.isReady(kStream)) k.decode(kStream)
                    val kw = k.getResult(kStream).keyword
                    if (kw.isNotEmpty()) {
                        k.reset(kStream)
                        capturing = true
                        onWake()
                    }
                } else {
                    // capturando o comando com VAD até o silêncio
                    val v = vad ?: break
                    v.acceptWaveform(samples)
                    while (!v.empty()) {
                        val seg = v.front()
                        val text = recognize(seg.samples)
                        v.pop()
                        capturing = false
                        if (text.isNotBlank()) onText(text)
                    }
                }
            }
        } catch (e: Throwable) {
            onError("Erro na captura: ${e.message}")
        } finally {
            try { rec.stop(); rec.release() } catch (_: Throwable) {}
        }
    }

    private fun recognize(samples: FloatArray): String {
        val r = asr ?: return ""
        return try {
            val s = r.createStream()
            s.acceptWaveform(samples, SAMPLE_RATE)
            r.decode(s)
            r.getResult(s).text.trim()
        } catch (e: Throwable) {
            Log.w(TAG, "recognize falhou: ${e.message}"); ""
        }
    }
}
