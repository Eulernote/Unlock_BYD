package com.euler.catalog

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.DataChannel
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.Executors

/**
 * Câmeras ao vivo (v60): câmera do carro → WebRTC → celular do cliente.
 *
 * Captura: android.hardware.AVMCamera (API escondida da BYD, validada na v59 no Dolphin Mini)
 * desenhando numa SurfaceTexture do WebRTC (SurfaceTextureHelper). Os quadros vão pro codificador
 * de hardware via DefaultVideoEncoderFactory.
 *
 * Um PeerConnection por celular assistindo; a câmera abre no 1º e fecha quando o último sai.
 * Tudo roda num executor único (ordem garantida, sem corrida entre start/stop).
 */
object LiveCam {
    private const val TAG = "LiveCam"
    private const val OUT_W = 1280
    private const val OUT_H = 720
    private const val OUT_FPS = 15
    private const val MAX_BITRATE = 1_500_000           // teto ~1,5 Mbps (a banda real decide)
    private const val MAX_SESSION_MS = 10 * 60 * 1000L   // trava de segurança no carro: 10 min por sessão
    private val PRIORITY = arrayOf("rvs", "rear", "front", "pano_h", "pano_l", "byd_apa", "apa", "rf")
    private val PANO = setOf("pano_h", "pano_l", "byd_apa", "apa")
    private val PANO_ORDER = listOf("pano_h", "pano_l", "byd_apa", "apa")
    // Visões do 360. Na faixa de 4 câmeras (ex.: 5120x960) a ordem, da esquerda p/ direita, é
    // ré | esquerda | direita | frente (mesma do Overdrive). Em câmera 2x2: frente TL, direita TR, ré BL, esquerda BR.
    private val STRIP_OFF = mapOf("v:front" to 0.75f, "v:right" to 0.5f, "v:rear" to 0.0f, "v:left" to 0.25f)
    private val GRID_Q = mapOf("v:front" to intArrayOf(0, 0), "v:right" to intArrayOf(1, 0), "v:rear" to intArrayOf(0, 1), "v:left" to intArrayOf(1, 1))
    private const val TILE_MAX_W = 960
    private const val GRID360_W = 1920   // 360: as 4 câmeras lado a lado em 1920 de largura (o celular monta a grade 2x2)

    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var factory: PeerConnectionFactory? = null
    private var egl: EglBase? = null
    private var source: VideoSource? = null
    private var track: VideoTrack? = null
    private var helper: SurfaceTextureHelper? = null
    private var avm: Any? = null
    private var camLabel = ""
    @Volatile private var viewKey = ""   // "" = imagem inteira; "v:front"... = recorte de uma câmera do 360
    private var camKey = ""              // o que o celular escolheu (tag real ou "v:...")
    private var camW = 0
    private var camH = 0
    private var defW = 0
    private var defH = 0
    private var sizeMode = "auto"

    private class Peer(val pc: PeerConnection, val send: (JSONObject) -> Unit, var offerSent: Boolean = false, var dc: DataChannel? = null, var mic: Boolean = false)

    @Volatile private var appCtx: Context? = null
    private val peers = HashMap<String, Peer>()
    private val timeouts = HashMap<String, Runnable>()

    @Volatile var lastStatus = "parado"; private set

    // ---------------- API usada pelo MonitorService ----------------
    /** cam: "auto", uma tag da BYD ("rvs", "front", "pano_h"…) ou "id:N". size: "auto" ou "LxA" (ex.: "1280x720"). */
    fun start(ctx: Context, v: String, ice: JSONArray?, cam: String, size: String, codec: String, send: (JSONObject) -> Unit) = exec.execute {
        try {
            ensureFactory(ctx.applicationContext)
            ensurePipeline()
            selectCam(cam, size)
            stopPeer(v, closeCamIfIdle = false)
            val pc = createPeer(v, ice, codec, send) ?: throw IllegalStateException("não consegui criar a conexão WebRTC")
            // Canal de dados "mic" (negociado, id 0): o áudio do carro vai por aqui quando o celular pede "Ouvir".
            val dc = try { pc.createDataChannel("mic", DataChannel.Init().apply { negotiated = true; id = 0; ordered = false; maxRetransmits = 0 }) } catch (t: Throwable) { null }
            peers[v] = Peer(pc, send, dc = dc)
            pc.createOffer(object : SdpAdapter() {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    exec.execute {
                        pc.setLocalDescription(object : SdpAdapter() {
                            override fun onSetSuccess() {
                                // Sem trickle: espera juntar os candidatos (ou 3 s) e manda a oferta completa.
                                main.postDelayed({ exec.execute { sendOffer(v, send) } }, 3000)
                            }
                            override fun onSetFailure(e: String?) { fail(v, send, "setLocal: $e") }
                        }, sdp)
                    }
                }
                override fun onCreateFailure(e: String?) { fail(v, send, "oferta: $e") }
            }, MediaConstraints())
            val t = Runnable { exec.execute { send(JSONObject().put("t", "error").put("v", v).put("msg", "tempo máximo da sessão")); stopPeer(v) } }
            timeouts[v] = t; main.postDelayed(t, MAX_SESSION_MS)
            send(info(v))
            lastStatus = "transmitindo ($camLabel ${camW}x$camH) p/ ${peers.size}"
        } catch (t: Throwable) {
            Log.e(TAG, "start", t)
            fail(v, send, msgOf(t))
        }
    }

    /** Troca de câmera/resolução sem refazer a conexão: reabre a câmera na mesma trilha de vídeo. */
    fun switchCam(v: String, cam: String, size: String) = exec.execute {
        val p = peers[v] ?: return@execute
        try {
            selectCam(cam, size)
            for ((k, peer) in peers) peer.send(info(k))
        } catch (t: Throwable) {
            Log.e(TAG, "switch", t)
            p.send(JSONObject().put("t", "error").put("v", v).put("msg", "câmera $cam: ${msgOf(t)}"))
            try { p.send(info(v)) } catch (_: Throwable) {}
        }
    }

    fun answer(v: String, sdp: String) = exec.execute {
        val p = peers[v] ?: return@execute
        p.pc.setRemoteDescription(object : SdpAdapter() {
            override fun onSetFailure(e: String?) { Log.w(TAG, "setRemote $e") }
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    /** Liga/desliga o microfone do carro para este celular. */
    fun mic(v: String, on: Boolean) = exec.execute {
        val p = peers[v] ?: return@execute
        try {
            if (on) {
                val c = appCtx ?: throw IllegalStateException("sem contexto")
                val dc = p.dc ?: throw IllegalStateException("esta conexão não tem canal de áudio — feche e abra a câmera de novo")
                if (dc.state() != DataChannel.State.OPEN) throw IllegalStateException("o canal de áudio ainda não abriu — tente de novo em 1 segundo")
                val r = AudioMic.start(c, dc)
                if (r.startsWith("ok")) {
                    p.mic = true
                    p.send(JSONObject().put("t", "mic").put("v", v).put("on", true).put("ok", true))
                } else {
                    p.mic = false
                    p.send(JSONObject().put("t", "mic").put("v", v).put("on", false).put("ok", false).put("msg", r))
                }
            } else {
                p.mic = false
                if (peers.values.none { it.mic }) AudioMic.stop()
                p.send(JSONObject().put("t", "mic").put("v", v).put("on", false).put("ok", true))
            }
        } catch (t: Throwable) {
            Log.e(TAG, "mic", t)
            p.send(JSONObject().put("t", "mic").put("v", v).put("on", false).put("ok", false).put("msg", msgOf(t)))
        }
    }

    /** Microfone falhou: tenta a próxima combinação (reinicia a transmissão) ou desiste depois de testar todas. */
    fun stop(v: String) = exec.execute { stopPeer(v) }
    fun stopAll() = exec.execute { for (k in peers.keys.toList()) stopPeer(k, closeCamIfIdle = false); closeCamera() }

    private fun info(v: String): JSONObject {
        val cams = JSONArray(); for (c in listCams()) cams.put(c)
        val o = JSONObject().put("t", "info").put("v", v).put("cam", camKey.ifEmpty { camLabel }).put("real", camLabel).put("w", camW).put("h", camH)
            .put("size", sizeMode).put("def", "${defW}x$defH").put("cams", cams)
        // 360 em faixa: o celular monta a grade 2x2 com estas posições (fração da largura de cada câmera na faixa).
        if (camKey == "v:360" && isStrip()) {
            val t = JSONObject(); for ((k, off) in STRIP_OFF) t.put(k, off.toDouble())
            o.put("layout", "strip4").put("tiles", t)
        } else if (camKey == "v:360") o.put("layout", "grid")
        return o
    }

    // contador de quadros: o carro manda a cada 2 s quantos quadros a câmera entregou (diagnóstico de tela preta)
    private val frames = java.util.concurrent.atomic.AtomicInteger(0)
    private var lastFrames = 0
    private val statsTick = object : Runnable {
        override fun run() {
            exec.execute {
                if (avm == null) return@execute
                val n = frames.get(); val fps = (n - lastFrames) / 2; lastFrames = n
                for ((k, peer) in peers) peerStats(k, peer, fps)
                main.postDelayed(this, 2000)
            }
        }
    }

    // ---------------- internos ----------------
    private fun fail(v: String, send: (JSONObject) -> Unit, msg: String) {
        try { send(JSONObject().put("t", "error").put("v", v).put("msg", msg)) } catch (_: Throwable) {}
        exec.execute { stopPeer(v) }
    }

    private fun msgOf(t: Throwable): String {
        var c: Throwable = t
        while (c.cause != null && c.cause !== c) c = c.cause!!
        return "${c.javaClass.simpleName}: ${c.message}"
    }

    private fun sendOffer(v: String, send: (JSONObject) -> Unit) {
        val p = peers[v] ?: return
        if (p.offerSent) return
        val sdp = p.pc.localDescription?.description ?: return fail(v, send, "sem oferta local")
        p.offerSent = true
        send(JSONObject().put("t", "offer").put("v", v).put("sdp", sdp))
    }

    private fun ensureFactory(ctx: Context) {
        if (factory != null) return
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx).createInitializationOptions())
        val e = EglBase.create(); egl = e
        appCtx = ctx
        // O áudio do carro vai pelo canal de dados (AudioMic), não pela captura do WebRTC (recusada neste BYD).
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(e.eglBaseContext, true, false))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(e.eglBaseContext))
            .createPeerConnectionFactory()
    }

    /** Troca a combinação de áudio e recria a fábrica (os vídeos são refeitos pelo reinício automático no celular). */
    private fun createPeer(v: String, ice: JSONArray?, codec: String, send: (JSONObject) -> Unit): PeerConnection? {
        val servers = ArrayList<PeerConnection.IceServer>()
        if (ice != null) for (i in 0 until ice.length()) {
            val o = ice.optJSONObject(i) ?: continue
            val urls = ArrayList<String>()
            when (val u = o.opt("urls")) {
                is JSONArray -> for (j in 0 until u.length()) urls += u.optString(j)
                is String -> urls += u
            }
            if (urls.isEmpty()) continue
            val b = PeerConnection.IceServer.builder(urls)
            if (o.has("username")) b.setUsername(o.optString("username"))
            if (o.has("credential")) b.setPassword(o.optString("credential"))
            servers += b.createIceServer()
        }
        if (servers.isEmpty()) servers += PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer()
        val cfg = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        val pc = factory!!.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {
                if (s == PeerConnection.IceGatheringState.COMPLETE) exec.execute { sendOffer(v, send) }
            }
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
                Log.i(TAG, "pc $v $s")
                if (s == PeerConnection.PeerConnectionState.FAILED) exec.execute { stopPeer(v) }
            }
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceCandidate(c: IceCandidate) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onDataChannel(d: DataChannel) {}
            override fun onRenegotiationNeeded() {}
        }) ?: return null
        val tr = pc.addTransceiver(track, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, listOf("cam")))
        try {
            val params = tr.sender.parameters
            for (enc in params.encodings) { enc.maxBitrateBps = MAX_BITRATE; enc.maxFramerate = OUT_FPS; enc.scaleResolutionDownBy = 1.0 }
            // Monitoramento: melhor imagem nítida com menos quadros do que imagem borrada (padrão do WebRTC é baixar a resolução).
            params.degradationPreference = org.webrtc.RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            tr.sender.setParameters(params)
        } catch (t: Throwable) { Log.w(TAG, "bitrate: ${t.message}") }
        // Padrão H264: o codificador de hardware (OMX.qcom...avc) deu a melhor imagem no teste.
        preferCodec(tr, if (codec == "VP8" || codec == "VP9") codec else "H264")
        return pc
    }

    /** Coloca o codec escolhido em primeiro (por reflexão: nem toda versão do WebRTC tem essa API). */
    private fun preferCodec(tr: RtpTransceiver, name: String) {
        try {
            val f = factory ?: return
            val mt = org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
            val caps = f.javaClass.getMethod("getRtpSenderCapabilities", mt.javaClass).invoke(f, mt) ?: return
            @Suppress("UNCHECKED_CAST")
            val codecs = caps.javaClass.getField("codecs").get(caps) as List<Any>
            fun nm(c: Any) = (c.javaClass.getField("name").get(c) as? String ?: "")
            val sorted = codecs.sortedBy { if (nm(it).equals(name, true)) 0 else 1 }
            tr.javaClass.getMethod("setCodecPreferences", List::class.java).invoke(tr, sorted)
        } catch (t: Throwable) { Log.w(TAG, "codec $name: ${t.message}") }
    }

    private val lastBytes = HashMap<String, Long>()
    /** Estatística do lado do carro: codificador usado, o que está limitando a qualidade, banda disponível. */
    private fun peerStats(v: String, p: Peer, camFps: Int) {
        try {
            p.pc.getStats { rep ->
                val m = rep.statsMap
                val o = JSONObject().put("t", "stats").put("v", v).put("fps", camFps)
                for (st in m.values) {
                    val mm = st.members
                    if (st.type == "outbound-rtp" && mm["kind"] == "video") {
                        o.put("enc", mm["encoderImplementation"]?.toString() ?: "")
                        o.put("lim", mm["qualityLimitationReason"]?.toString() ?: "")
                        o.put("ow", (mm["frameWidth"] as? Number)?.toInt() ?: 0).put("oh", (mm["frameHeight"] as? Number)?.toInt() ?: 0)
                        o.put("ofps", (mm["framesPerSecond"] as? Number)?.toDouble() ?: 0.0)
                        val b = (mm["bytesSent"] as? Number)?.toLong() ?: 0L
                        val prev = lastBytes[v]; lastBytes[v] = b
                        if (prev != null) o.put("kbps", (b - prev) * 8 / 2000)
                        val codecId = mm["codecId"]?.toString()
                        if (codecId != null) o.put("codec", m[codecId]?.members?.get("mimeType")?.toString() ?: "")
                    } else if (st.type == "candidate-pair" && mm["nominated"] == true && mm["state"] == "succeeded") {
                        o.put("aob", ((mm["availableOutgoingBitrate"] as? Number)?.toDouble() ?: 0.0) / 1000)
                        o.put("rtt", ((mm["currentRoundTripTime"] as? Number)?.toDouble() ?: 0.0) * 1000)
                        val lid = mm["localCandidateId"]?.toString()
                        if (lid != null) o.put("net", m[lid]?.members?.get("candidateType")?.toString() ?: "")
                    }
                }
                p.send(o)
            }
        } catch (t: Throwable) { p.send(JSONObject().put("t", "stats").put("v", v).put("fps", camFps)) }
    }

    private fun stopPeer(v: String, closeCamIfIdle: Boolean = true) {
        timeouts.remove(v)?.let { main.removeCallbacks(it) }
        peers.remove(v)?.let { try { it.pc.dispose() } catch (_: Throwable) {} }
        if (peers.values.none { it.mic }) try { AudioMic.stop() } catch (_: Throwable) {}
        if (closeCamIfIdle && peers.isEmpty()) closeCamera()
        lastStatus = if (peers.isEmpty()) "parado" else "transmitindo p/ ${peers.size}"
    }

    // ---------------- câmera (AVMCamera da BYD) ----------------
    private fun bydClass(name: String): Class<*>? {
        try { return Class.forName(name) } catch (_: Throwable) {}
        val jar = File("/system/framework/bmmcamera.jar")
        if (!jar.exists()) return null
        try {
            val cl = LiveCam::class.java.classLoader!!
            val m = cl.javaClass.getMethod("addDexPath", String::class.java); m.isAccessible = true
            m.invoke(cl, jar.absolutePath)
            return Class.forName(name)
        } catch (_: Throwable) {}
        return try { Class.forName(name, true, dalvik.system.PathClassLoader(jar.absolutePath, LiveCam::class.java.classLoader)) } catch (_: Throwable) { null }
    }

    private fun method(cls: Class<*>, name: String, argc: Int): Method? =
        (cls.methods + cls.declaredMethods).firstOrNull { it.name == name && it.parameterTypes.size == argc }?.also { it.isAccessible = true }

    private fun cameraId(tag: String): Int {
        if (tag.startsWith("id:")) return tag.substring(3).toIntOrNull() ?: -1
        val info = bydClass("android.hardware.BmmCameraInfo") ?: return -1
        val getId = method(info, "getCameraId", 1) ?: return -1
        return try { getId.invoke(null, tag) as? Int ?: -1 } catch (_: Throwable) { -1 }
    }

    /** Câmeras que o carro declara (tags da BYD) + ids crus 0..5 pra testar uma a uma. */
    private fun panoTag(): String? = PANO_ORDER.firstOrNull { cameraId(it) >= 0 }
    private fun isStrip() = camH > 0 && camW.toFloat() / camH > 2.5f

    private fun listCams(): List<String> {
        val out = ArrayList<String>(); val ids = HashSet<Int>()
        if (panoTag() != null) out.addAll(listOf("v:360", "v:front", "v:rear", "v:left", "v:right"))
        for (t in PRIORITY + arrayOf("dms", "face", "d954_h_m", "d954_h_s", "d954_l_m", "d954_l_s")) {
            val id = cameraId(t); if (id >= 0 && t !in out) { out += t; ids += id }
        }
        for (i in 0..5) if (i !in ids) out += "id:$i"
        return out
    }

    private fun ensurePipeline() {
        if (helper != null) return
        val f = factory!!
        val h = SurfaceTextureHelper.create("unlock-cam", egl!!.eglBaseContext) ?: throw IllegalStateException("SurfaceTextureHelper")
        val src = f.createVideoSource(false)
        val trk = f.createVideoTrack("cam0", src)
        helper = h; source = src; track = trk
        h.setTextureSize(1280, 720)
        src.capturerObserver.onCapturerStarted(true)
        // A câmera da BYD não avança o horário dos quadros: o WebRTC achava que eram repetidos e descartava
        // tudo depois do 1º (vídeo congelado, "envia 0 q/s"). Carimbamos cada quadro com o relógio do carro.
        h.startListening { frame ->
            frames.incrementAndGet()
            val r = viewRect(viewKey, frame.buffer.width, frame.buffer.height)
            if (r != null) {
                // Uma câmera só do 360: recorta o pedaço dela (barato: é só a matriz da textura) e escala.
                val ow = minOf(TILE_MAX_W, r[2]); val oh = (ow.toLong() * r[3] / r[2]).toInt() / 2 * 2
                val b = frame.buffer.cropAndScale(r[0], r[1], r[2], r[3], ow, oh)
                try { source?.capturerObserver?.onFrameCaptured(org.webrtc.VideoFrame(b, frame.rotation, System.nanoTime())) }
                finally { b.release() }
            } else {
                val stamped = org.webrtc.VideoFrame(frame.buffer, frame.rotation, System.nanoTime())
                source?.capturerObserver?.onFrameCaptured(stamped)
            }
        }
        lastFrames = frames.get()
        main.removeCallbacks(statsTick); main.postDelayed(statsTick, 2000)
    }

    /** Recorte (x, y, largura, altura) de uma câmera dentro da imagem do 360, ou null = imagem inteira. */
    private fun viewRect(key: String, fw: Int, fh: Int): IntArray? {
        if (key.isEmpty() || fw <= 0 || fh <= 0) return null
        return if (fw.toFloat() / fh > 2.5f) {
            val off = STRIP_OFF[key] ?: return null
            val cw = fw / 4
            intArrayOf((fw * off).toInt().coerceIn(0, fw - cw), 0, cw, fh)
        } else {
            val q = GRID_Q[key] ?: return null
            intArrayOf(q[0] * fw / 2, q[1] * fh / 2, fw / 2, fh / 2)
        }
    }

    /** Escolhe câmera real ou visão do 360 (frente/ré/esquerda/direita/360) sem reabrir a câmera à toa. */
    private fun selectCam(want0: String, size: String) {
        val pano = panoTag()
        val want = if ((want0 == "auto" || want0.isBlank()) && pano != null) "v:360" else want0
        if (want.startsWith("v:")) {
            val base = pano ?: throw IllegalStateException("este carro não tem câmera 360")
            if (avm == null || camLabel != base || size != sizeMode) openAvm(base, size)
            viewKey = if (want == "v:360") "" else want
            camKey = want
        } else {
            if (avm == null || (want != "auto" && want != camLabel) || size != sizeMode) openAvm(want, size)
            viewKey = ""; camKey = camLabel
        }
        applyAdapt()
    }

    private fun applyAdapt() {
        val w = camW; val h = camH
        if (w <= 0 || h <= 0) return
        if (viewKey.isNotEmpty()) {
            val strip = isStrip()
            val tw = if (strip) w / 4 else w / 2; val th = if (strip) h else h / 2
            val ow = minOf(TILE_MAX_W, tw); val oh = (ow.toLong() * th / tw).toInt() / 2 * 2
            source?.adaptOutputFormat(ow, oh, OUT_FPS)
        } else if (isStrip()) {
            val ow = minOf(w, GRID360_W); source?.adaptOutputFormat(ow, (ow.toLong() * h / w).toInt().coerceAtLeast(90) / 2 * 2, OUT_FPS)
        } else {
            val ow = minOf(w, OUT_W); source?.adaptOutputFormat(ow, (ow.toLong() * h / w).toInt().coerceAtLeast(90) / 2 * 2, OUT_FPS)
        }
    }

    private fun openAvm(want: String, size: String) {
        closeAvm()
        val h = helper ?: throw IllegalStateException("pipeline")
        val tag = if (want == "auto" || want.isBlank()) (PRIORITY.firstOrNull { cameraId(it) >= 0 } ?: "id:0") else want
        val id = cameraId(tag)
        if (id < 0) throw IllegalStateException("o carro não tem a câmera '$tag'")
        val cls = bydClass("android.hardware.AVMCamera") ?: throw IllegalStateException("este carro não tem a câmera da BYD (AVMCamera)")
        var obj: Any? = try { method(cls, "open", 1)?.invoke(null, id) } catch (_: Throwable) { null }
        if (obj == null || obj is Boolean) {
            val ctor = cls.getDeclaredConstructor(Int::class.javaPrimitiveType); ctor.isAccessible = true
            obj = ctor.newInstance(id)
            if (method(cls, "open", 0)?.invoke(obj) == false) throw IllegalStateException("câmera ocupada (id $id)")
        }
        avm = obj
        try { method(cls, "setCameraFps", 1)?.invoke(obj, 25) } catch (_: Throwable) {}
        // Tamanho padrão da câmera (antes de qualquer setPreviewSize).
        defW = try { method(cls, "getPreviewWidth", 0)?.invoke(obj) as? Int ?: 0 } catch (_: Throwable) { 0 }
        defH = try { method(cls, "getPreviewHeight", 0)?.invoke(obj) as? Int ?: 0 } catch (_: Throwable) { 0 }
        var w: Int; var hh: Int
        val m = Regex("^(\\d+)x(\\d+)$").find(size)
        if (m != null) { w = m.groupValues[1].toInt(); hh = m.groupValues[2].toInt() }
        else if (defW > 0 && defH > 0) { w = defW; hh = defH }
        else if (tag in PANO) { w = 5120; hh = 960 }
        else { w = 720; hh = 576 }    // a câmera não informa o tamanho (0x0); 720x576 com H264 foi a melhor imagem no Dolphin
        if (m != null || defW <= 0 || defH <= 0) try { method(cls, "setPreviewSize", 2)?.invoke(obj, w, hh) } catch (_: Throwable) {}
        sizeMode = if (m != null) size else "auto"
        // O buffer da imagem PRECISA ter o mesmo tamanho que a câmera desenha (senão não chega quadro).
        h.setTextureSize(w, hh)
        val st: SurfaceTexture = h.surfaceTexture
        st.setDefaultBufferSize(w, hh)
        var bound = false
        for (mName in listOf("addTexture", "setTexture")) {
            val mm = (cls.methods + cls.declaredMethods).firstOrNull { it.name == mName && it.parameterTypes.size == 2 && it.parameterTypes[0] == SurfaceTexture::class.java } ?: continue
            mm.isAccessible = true
            try { if (mm.invoke(obj, st, 0) != false) bound = true } catch (_: Throwable) {}
        }
        if (!bound) throw IllegalStateException("não consegui ligar a imagem da câmera")
        if (method(cls, "startPreview", 0)?.invoke(obj) == false) throw IllegalStateException("startPreview falhou")
        camW = w; camH = hh; camLabel = tag
        viewKey = ""; camKey = tag
        Log.i(TAG, "câmera $tag (id $id) ${w}x$hh (padrão ${defW}x$defH) aberta")
    }

    private fun closeAvm() {
        val o = avm ?: return
        avm = null
        val c = o.javaClass
        try { helper?.surfaceTexture?.let { st -> c.methods.firstOrNull { it.name == "rmTexture" && it.parameterTypes.size == 2 }?.invoke(o, st, 0) } } catch (_: Throwable) {}
        for (n in listOf("stopPreview", "close")) try { method(c, n, 0)?.invoke(o) } catch (_: Throwable) {}
    }

    private fun closeCamera() {
        val had = avm != null
        closeAvm()
        main.removeCallbacks(statsTick)
        try { helper?.stopListening() } catch (_: Throwable) {}
        try { source?.capturerObserver?.onCapturerStopped() } catch (_: Throwable) {}
        try { track?.dispose() } catch (_: Throwable) {}
        try { source?.dispose() } catch (_: Throwable) {}
        try { helper?.dispose() } catch (_: Throwable) {}
        track = null; source = null; helper = null; camLabel = ""; camKey = ""; viewKey = ""
        if (had) Log.i(TAG, "câmera fechada")
    }

    private open class SdpAdapter : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) {}
        override fun onSetFailure(e: String?) {}
    }
}
