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

    private class Peer(val pc: PeerConnection, var offerSent: Boolean = false)
    private val peers = HashMap<String, Peer>()
    private val timeouts = HashMap<String, Runnable>()

    @Volatile var lastStatus = "parado"; private set

    // ---------------- API usada pelo MonitorService ----------------
    fun start(ctx: Context, v: String, ice: JSONArray?, send: (JSONObject) -> Unit) = exec.execute {
        try {
            ensureFactory(ctx.applicationContext)
            ensureCamera()
            stopPeer(v, closeCamIfIdle = false)
            val pc = createPeer(v, ice, send) ?: throw IllegalStateException("não consegui criar a conexão WebRTC")
            peers[v] = Peer(pc)
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
            send(JSONObject().put("t", "info").put("v", v).put("cam", camLabel).put("w", camW).put("h", camH))
            lastStatus = "transmitindo ($camLabel ${camW}x$camH) p/ ${peers.size}"
        } catch (t: Throwable) {
            Log.e(TAG, "start", t)
            fail(v, send, msgOf(t))
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

    private fun pickCamera(): Pair<String, Int> {
        val info = bydClass("android.hardware.BmmCameraInfo")
        val getId = info?.let { method(it, "getCameraId", 1) }
        if (getId != null) for (tag in PRIORITY) {
            val id = try { getId.invoke(null, tag) as? Int ?: -1 } catch (_: Throwable) { -1 }
            if (id >= 0) return tag to id
        }
        return "id0" to 0
    }

    private fun ensureCamera() {
        if (avm != null) return
        val f = factory!!
        val h = SurfaceTextureHelper.create("unlock-cam", egl!!.eglBaseContext) ?: throw IllegalStateException("SurfaceTextureHelper")
        val src = f.createVideoSource(false)
        val trk = f.createVideoTrack("cam0", src)
        helper = h; source = src; track = trk

        val (tag, id) = pickCamera()
        val cls = bydClass("android.hardware.AVMCamera") ?: throw IllegalStateException("este carro não tem a câmera da BYD (AVMCamera)")
        var obj: Any? = try { method(cls, "open", 1)?.invoke(null, id) } catch (_: Throwable) { null }
        if (obj == null || obj is Boolean) {
            val ctor = cls.getDeclaredConstructor(Int::class.javaPrimitiveType); ctor.isAccessible = true
            obj = ctor.newInstance(id)
            if (method(cls, "open", 0)?.invoke(obj) == false) throw IllegalStateException("câmera ocupada (id $id)")
        }
        avm = obj
        try { method(cls, "setCameraFps", 1)?.invoke(obj, 25) } catch (_: Throwable) {}
        // Mesma sequência validada no teste da v59 (setPreviewSize 5120x960 funcionou no Dolphin).
        try { method(cls, "setPreviewSize", 2)?.invoke(obj, 5120, 960) } catch (_: Throwable) {}

        val st: SurfaceTexture = h.surfaceTexture
        h.setTextureSize(1280, 720)  // provisório; corrigido abaixo com o tamanho real
        var bound = false
        for (mName in listOf("addTexture", "setTexture")) {
            val m = (cls.methods + cls.declaredMethods).firstOrNull { it.name == mName && it.parameterTypes.size == 2 && it.parameterTypes[0] == SurfaceTexture::class.java } ?: continue
            m.isAccessible = true
            try { if (m.invoke(obj, st, 0) != false) bound = true } catch (_: Throwable) {}
        }
        if (!bound) throw IllegalStateException("não consegui ligar a imagem da câmera")
        val ok = method(cls, "startPreview", 0)?.invoke(obj)
        if (ok == false) throw IllegalStateException("startPreview falhou")

        // Tamanho real que a câmera está entregando.
        var w = try { method(cls, "getPreviewWidth", 0)?.invoke(obj) as? Int ?: 0 } catch (_: Throwable) { 0 }
        var hh = try { method(cls, "getPreviewHeight", 0)?.invoke(obj) as? Int ?: 0 } catch (_: Throwable) { 0 }
        if (w <= 0 || hh <= 0) { w = 1280; hh = 720 }
        camW = w; camH = hh; camLabel = tag
        h.setTextureSize(w, hh)
        // Faixa panorâmica (4 câmeras lado a lado) fica muito larga: limita pela largura mantendo a proporção.
        if (tag in PANO || w.toFloat() / hh > 2.5f) src.adaptOutputFormat(1920, (1920f * hh / w).toInt().coerceAtLeast(120) / 2 * 2, OUT_FPS)
        else src.adaptOutputFormat(OUT_W, OUT_H, OUT_FPS)
        src.capturerObserver.onCapturerStarted(true)
        h.startListening { frame -> source?.capturerObserver?.onFrameCaptured(frame) }
        Log.i(TAG, "câmera $tag (id $id) ${w}x$hh aberta")
    }

    private fun closeCamera() {
        val o = avm
        avm = null
        if (o != null) {
            val c = o.javaClass
            try { helper?.surfaceTexture?.let { st -> c.methods.firstOrNull { it.name == "rmTexture" && it.parameterTypes.size == 2 }?.invoke(o, st, 0) } } catch (_: Throwable) {}
            for (n in listOf("stopPreview", "close")) try { method(c, n, 0)?.invoke(o) } catch (_: Throwable) {}
        }
        try { helper?.stopListening() } catch (_: Throwable) {}
        try { source?.capturerObserver?.onCapturerStopped() } catch (_: Throwable) {}
        try { track?.dispose() } catch (_: Throwable) {}
        try { source?.dispose() } catch (_: Throwable) {}
        try { helper?.dispose() } catch (_: Throwable) {}
        track = null; source = null; helper = null
        if (o != null) Log.i(TAG, "câmera fechada")
    }

    private open class SdpAdapter : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) {}
        override fun onSetFailure(e: String?) {}
    }
}
