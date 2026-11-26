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
    private const val OUT_FPS = 20
    private const val MAX_BITRATE = 1_000_000           // ~1 Mbps (≈ 450 MB por hora assistindo)
    private const val MAX_SESSION_MS = 10 * 60 * 1000L   // trava de segurança no carro: 10 min por sessão
    private val PRIORITY = arrayOf("rvs", "rear", "front", "pano_h", "pano_l", "byd_apa", "apa", "rf")
    private val PANO = setOf("pano_h", "pano_l", "byd_apa", "apa")

    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var factory: PeerConnectionFactory? = null
    private var egl: EglBase? = null
    private var source: VideoSource? = null
    private var track: VideoTrack? = null
    private var helper: SurfaceTextureHelper? = null
    private var avm: Any? = null
    private var camLabel = ""
    private var camW = 0
    private var camH = 0
    private var defW = 0
    private var defH = 0
    private var sizeMode = "auto"

    private class Peer(val pc: PeerConnection, val send: (JSONObject) -> Unit, var offerSent: Boolean = false)
    private val peers = HashMap<String, Peer>()
    private val timeouts = HashMap<String, Runnable>()

    @Volatile var lastStatus = "parado"; private set

    // ---------------- API usada pelo MonitorService ----------------
    /** cam: "auto", uma tag da BYD ("rvs", "front", "pano_h"…) ou "id:N". size: "auto" ou "LxA" (ex.: "1280x720"). */
    fun start(ctx: Context, v: String, ice: JSONArray?, cam: String, size: String, send: (JSONObject) -> Unit) = exec.execute {
        try {
            ensureFactory(ctx.applicationContext)
            ensurePipeline()
            if (avm == null || (cam != "auto" && cam != camLabel) || size != sizeMode) openAvm(cam, size)
            stopPeer(v, closeCamIfIdle = false)
            val pc = createPeer(v, ice, send) ?: throw IllegalStateException("não consegui criar a conexão WebRTC")
            peers[v] = Peer(pc, send)
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
            openAvm(cam, size)
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

    fun stop(v: String) = exec.execute { stopPeer(v) }
    fun stopAll() = exec.execute { for (k in peers.keys.toList()) stopPeer(k, closeCamIfIdle = false); closeCamera() }

    private fun info(v: String): JSONObject {
        val cams = JSONArray(); for (c in listCams()) cams.put(c)
        return JSONObject().put("t", "info").put("v", v).put("cam", camLabel).put("w", camW).put("h", camH)
            .put("size", sizeMode).put("def", "${defW}x$defH").put("cams", cams)
    }

    // contador de quadros: o carro manda a cada 2 s quantos quadros a câmera entregou (diagnóstico de tela preta)
    private val frames = java.util.concurrent.atomic.AtomicInteger(0)
    private var lastFrames = 0
    private val statsTick = object : Runnable {
        override fun run() {
            exec.execute {
                if (avm == null) return@execute
                val n = frames.get(); val fps = (n - lastFrames) / 2; lastFrames = n
                for ((k, peer) in peers) peer.send(JSONObject().put("t", "stats").put("v", k).put("fps", fps).put("total", n))
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
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(e.eglBaseContext, true, false))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(e.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private fun createPeer(v: String, ice: JSONArray?, send: (JSONObject) -> Unit): PeerConnection? {
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
            for (enc in params.encodings) { enc.maxBitrateBps = MAX_BITRATE; enc.maxFramerate = OUT_FPS }
            tr.sender.setParameters(params)
        } catch (t: Throwable) { Log.w(TAG, "bitrate: ${t.message}") }
        return pc
    }

    private fun stopPeer(v: String, closeCamIfIdle: Boolean = true) {
        timeouts.remove(v)?.let { main.removeCallbacks(it) }
        peers.remove(v)?.let { try { it.pc.dispose() } catch (_: Throwable) {} }
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
    private fun listCams(): List<String> {
        val out = ArrayList<String>(); val ids = HashSet<Int>()
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
        h.startListening { frame -> frames.incrementAndGet(); source?.capturerObserver?.onFrameCaptured(frame) }
        lastFrames = frames.get()
        main.removeCallbacks(statsTick); main.postDelayed(statsTick, 2000)
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
        else { w = 1280; hh = 720 }
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
        // Saída: até 1280 de largura mantendo a proporção (faixa panorâmica: até 1920).
        val maxW = if (w.toFloat() / hh > 2.5f) 1920 else OUT_W
        val ow = minOf(w, maxW); val oh = (ow.toLong() * hh / w).toInt().coerceAtLeast(90) / 2 * 2
        source?.adaptOutputFormat(ow, oh, OUT_FPS)
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
        track = null; source = null; helper = null; camLabel = ""
        if (had) Log.i(TAG, "câmera fechada")
    }

    private open class SdpAdapter : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) {}
        override fun onSetFailure(e: String?) {}
    }
}
