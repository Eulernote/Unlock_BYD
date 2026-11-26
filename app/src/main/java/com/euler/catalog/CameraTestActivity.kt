package com.euler.catalog

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TESTE DE CÂMERAS (v59) — só descoberta, sem transmissão.
 *
 * 1) Diagnóstico: descobre quais câmeras o carro expõe pela API escondida da BYD
 *    (android.hardware.BmmCameraInfo / AVMCamera, em /system/framework/bmmcamera.jar)
 *    e pela Camera2 padrão do Android.
 * 2) Abre cada câmera e mostra a imagem nesta tela, contando quadros (fps) — se o contador
 *    sobe, a captura funciona e o resto (transmitir pro celular) é engenharia conhecida.
 *
 * Tudo por reflexão e com try/catch em cada passo: em carro que não tem a API, só registra no log.
 */
class CameraTestActivity : Activity() {

    private val BG = 0xFF0A0C10.toInt(); private val SURFACE2 = 0xFF1A2029.toInt()
    private val TEXT = 0xFFE8ECF3.toInt(); private val MUTED = 0xFF8793A6.toInt()
    private val ACCENT = 0xFF2BB0A6.toInt(); private val RED = 0xFFEF5350.toInt()

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var preview: TextureView
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var camRow: LinearLayout
    private val log = StringBuilder()

    private val TAGS = arrayOf("front", "rear", "rvs", "rf", "dms", "face", "pano_h", "pano_l",
        "apa", "byd_apa", "d954_h_m", "d954_h_s", "d954_l_m", "d954_l_s")

    // estado da câmera aberta
    private var avm: Any? = null
    private var cam2: CameraDevice? = null
    private var cam2Session: CameraCaptureSession? = null
    private var camThread: HandlerThread? = null
    private var frames = 0
    private var framesAtStart = 0L
    private var current = ""

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(9).toFloat() }
    private fun btn(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 13f; setTextColor(TEXT); background = rounded(SURFACE2)
        setPadding(dp(14), dp(6), dp(14), dp(6)); minHeight = 0; minimumHeight = 0
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.rightMargin = dp(8); lp.bottomMargin = dp(8); layoutParams = lp
        setOnClickListener { onClick() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this); root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG); root.setPadding(dp(16), dp(14), dp(16), dp(10))

        root.addView(TextView(this).apply { text = "Teste de câmeras (v${ownVc()})"; textSize = 17f; setTextColor(TEXT); setTypeface(typeface, Typeface.BOLD) })
        root.addView(TextView(this).apply { text = "1) Rode o diagnóstico  2) Toque numa câmera  3) Veja se a imagem aparece e o contador de quadros sobe. Fotografe o log no fim."; textSize = 12f; setTextColor(MUTED); setPadding(0, dp(2), 0, dp(10)) })

        val actions = LinearLayout(this); actions.orientation = LinearLayout.HORIZONTAL
        actions.addView(btn("① Diagnóstico") { runProbe() })
        actions.addView(btn("Diagnóstico via ADB") { runAdbProbe() })
        actions.addView(btn("■ Fechar câmera") { closeAll(); setStatus("Câmera fechada", MUTED) })
        actions.addView(btn("Limpar log") { log.setLength(0); logView.text = "" })
        root.addView(HorizontalScrollView(this).apply { addView(actions) })

        camRow = LinearLayout(this); camRow.orientation = LinearLayout.HORIZONTAL
        root.addView(HorizontalScrollView(this).apply { addView(camRow) })

        status = TextView(this).apply { text = "Nenhuma câmera aberta"; textSize = 13f; setTextColor(MUTED); setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(4), 0, dp(6)) }
        root.addView(status)

        // Área de imagem + log lado a lado (tela da multimídia é larga).
        val body = LinearLayout(this); body.orientation = LinearLayout.HORIZONTAL
        preview = TextureView(this)
        preview.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.4f).apply { rightMargin = dp(10) }
        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { L("preview pronto ${w}x$h") }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { closeAll(); return true }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) { frames++ }
        }
        body.addView(preview)
        logView = TextView(this).apply {
            textSize = 11.5f; setTextColor(0xFF000000.toInt()); setBackgroundColor(0xFFFFFFFF.toInt())
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD); setPadding(dp(10), dp(8), dp(10), dp(8)); setTextIsSelectable(true)
        }
        val logScroll = ScrollView(this).apply { setBackgroundColor(0xFFFFFFFF.toInt()); addView(logView) }
        logScroll.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        body.addView(logScroll)
        root.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        L("modelo=${Build.MODEL} fab=${Build.MANUFACTURER} sdk=${Build.VERSION.SDK_INT} build=${Build.DISPLAY}")
        ui.postDelayed(fpsTick, 1000)
        ensureCameraPermission()
    }

    private fun ownVc(): Int = try {
        @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, 0).versionCode
    } catch (e: Exception) { 0 }

    // ---------------- log / status ----------------
    private fun L(s: String) {
        val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + " " + s
        android.util.Log.i("CamTest", line)
        ui.post { log.append(line).append('\n'); logView.text = log }
    }
    private fun setStatus(s: String, color: Int) = ui.post { status.text = s; status.setTextColor(color) }
    private fun err(t: Throwable): String {
        var c: Throwable = t
        while (c.cause != null && c.cause !== c) c = c.cause!!
        return "${c.javaClass.simpleName}: ${c.message}"
    }

    private val fpsTick = object : Runnable {
        override fun run() {
            if (current.isNotEmpty()) {
                val secs = ((System.currentTimeMillis() - framesAtStart) / 1000).coerceAtLeast(1)
                val ok = frames > 0
                status.text = "$current · quadros: $frames (~${frames / secs} fps)" + if (ok) "  ✓ IMAGEM CHEGANDO" else "  … aguardando imagem"
                status.setTextColor(if (ok) ACCENT else MUTED)
            }
            ui.postDelayed(this, 1000)
        }
    }

    // ---------------- permissão ----------------
    private fun ensureCameraPermission() {
        if (Build.VERSION.SDK_INT < 23) return
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) { L("permissão CAMERA: ok"); return }
        L("permissão CAMERA: pedindo…")
        requestPermissions(arrayOf(Manifest.permission.CAMERA), 7)
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        L("permissão CAMERA: " + if (ok) "concedida" else "NEGADA — tentando conceder via ADB…")
        if (!ok) Thread {
            val r = AdbShell.run(applicationContext, "pm grant $packageName android.permission.CAMERA")
            L("ADB pm grant CAMERA: " + if (r.ok) "ok" else "falhou (${r.reason} ${r.output})")
        }.start()
    }

    // ---------------- reflexão ----------------
    /** Garante que as classes da BYD estejam visíveis (alguns firmwares exigem carregar o bmmcamera.jar). */
    private fun bydClass(name: String): Class<*>? {
        try { return Class.forName(name) } catch (_: Throwable) {}
        val jar = File("/system/framework/bmmcamera.jar")
        if (jar.exists()) {
            try {
                val cl = classLoader
                val m = cl.javaClass.getMethod("addDexPath", String::class.java); m.isAccessible = true
                m.invoke(cl, jar.absolutePath)
                return Class.forName(name)
            } catch (_: Throwable) {}
            try {
                val dcl = dalvik.system.PathClassLoader(jar.absolutePath, classLoader)
                return Class.forName(name, true, dcl)
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun findMethod(cls: Class<*>, name: String, argc: Int): Method? {
        (cls.methods + cls.declaredMethods).firstOrNull { it.name == name && it.parameterTypes.size == argc }?.let { it.isAccessible = true; return it }
        return null
    }

    private fun sig(m: Method) = m.name + "(" + m.parameterTypes.joinToString(",") { it.simpleName } + ")" +
        (if (java.lang.reflect.Modifier.isStatic(m.modifiers)) " [static]" else "")

    // ---------------- diagnóstico ----------------
    private fun runProbe() {
        camRow.removeAllViews()
        L("===== DIAGNÓSTICO =====")
        Thread {
            try {
                val sp = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                for (k in listOf("vehicle.config.cam_sort", "ro.product.model", "ro.build.version.incremental", "persist.sys.avm.type", "ro.vendor.avm"))
                    L("prop $k = ${sp.invoke(null, k)}")
            } catch (t: Throwable) { L("props: ${err(t)}") }

            L("bmmcamera.jar existe: ${File("/system/framework/bmmcamera.jar").exists()}")
            val infoCls = bydClass("android.hardware.BmmCameraInfo")
            val avmCls = bydClass("android.hardware.AVMCamera")
            L("BmmCameraInfo: ${if (infoCls != null) "ENCONTRADA" else "não existe"} · AVMCamera: ${if (avmCls != null) "ENCONTRADA" else "não existe"}")

            val avmIds = linkedMapOf<String, Int>()
            if (infoCls != null) {
                val getId = findMethod(infoCls, "getCameraId", 1)
                if (getId == null) L("getCameraId não existe")
                for (t in TAGS) try {
                    val id = (getId?.invoke(null, t) as? Int) ?: -1
                    if (id >= 0) { avmIds[t] = id; L("  câmera '$t' = id $id") }
                } catch (e: Throwable) { L("  '$t': ${err(e)}") }
                if (avmIds.isEmpty()) L("  nenhuma tag retornou id ≥ 0")
                for (i in 0..7) try {
                    val w = findMethod(infoCls, "getDefaultPreviewWidth", 1)?.invoke(null, i) as? Int ?: 0
                    val h = findMethod(infoCls, "getDefaultPreviewHeight", 1)?.invoke(null, i) as? Int ?: 0
                    if (w > 0 && h > 0) L("  tamanho padrão id $i = ${w}x$h")
                } catch (_: Throwable) {}
                L("  métodos BmmCameraInfo: " + infoCls.declaredMethods.joinToString(" | ") { sig(it) }.take(1500))
            }
            if (avmCls != null) {
                L("  métodos AVMCamera: " + avmCls.declaredMethods.joinToString(" | ") { sig(it) }.take(2500))
                L("  construtores: " + avmCls.declaredConstructors.joinToString(" | ") { it.parameterTypes.joinToString(",") { p -> p.simpleName } })
            }

            // Camera2 padrão
            val cam2Ids = mutableListOf<String>()
            try {
                val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                for (id in cm.cameraIdList) {
                    cam2Ids += id
                    val ch = cm.getCameraCharacteristics(id)
                    val facing = when (ch.get(CameraCharacteristics.LENS_FACING)) { 0 -> "frente"; 1 -> "trás"; 2 -> "externa"; else -> "?" }
                    val sizes = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(SurfaceTexture::class.java)?.take(4)?.joinToString(",") ?: "-"
                    L("  Camera2 id $id ($facing) tamanhos: $sizes")
                }
                if (cam2Ids.isEmpty()) L("  Camera2: nenhuma câmera listada")
            } catch (t: Throwable) { L("  Camera2: ${err(t)}") }

            ui.post {
                for ((tag, id) in avmIds) camRow.addView(btn("AVM $tag ($id)") { openAvm(id, "AVM $tag ($id)") })
                if (avmCls != null) for (i in 0..5) if (!avmIds.containsValue(i)) camRow.addView(btn("AVM id $i") { openAvm(i, "AVM id $i") })
                for (id in cam2Ids) camRow.addView(btn("Camera2 $id") { openCam2(id) })
                if (camRow.childCount == 0) camRow.addView(TextView(this).apply { text = "Nenhuma câmera encontrada — rode o diagnóstico via ADB e me mande o log"; setTextColor(RED); textSize = 13f })
            }
            L("===== fim do diagnóstico — toque numa câmera acima =====")
        }.start()
    }

    /** Coleta extra pelo shell (precisa da depuração ativa). Ajuda a entender carros sem a API. */
    private fun runAdbProbe() {
        L("===== DIAGNÓSTICO VIA ADB (toque em 'Permitir' se aparecer) =====")
        Thread {
            val cmd = "ls /system/framework | grep -iE 'cam|bmm|avm'; echo '--- props'; getprop | grep -iE 'cam|avm|pano' | head -40; " +
                "echo '--- media.camera'; dumpsys media.camera 2>/dev/null | grep -iE 'Camera [0-9]|Device|facing|API|Client' | head -40; " +
                "echo '--- serviços'; service list | grep -iE 'cam|avm|bmm|acc' | head -20; echo '--- pacotes'; pm list packages | grep -iE 'avm|camera|pano|360' | head -20"
            val r = AdbShell.run(applicationContext, cmd, 60000)
            L(if (r.output.isNotBlank()) r.output else "sem saída (${r.reason})")
            L("===== fim ADB =====")
        }.start()
    }

    // ---------------- abrir câmeras ----------------
    private fun startCounting(label: String) {
        current = label; frames = 0; framesAtStart = System.currentTimeMillis()
        setStatus("$label · abrindo…", MUTED)
    }

    private fun openAvm(id: Int, label: String) {
        closeAll()
        val st = preview.surfaceTexture ?: run { L("preview ainda não está pronto"); return }
        startCounting(label)
        L("--- abrindo $label")
        Thread {
            try {
                val cls = bydClass("android.hardware.AVMCamera") ?: throw IllegalStateException("AVMCamera não existe")
                var obj: Any? = null
                try {
                    obj = findMethod(cls, "open", 1)?.takeIf { java.lang.reflect.Modifier.isStatic(it.modifiers) }?.invoke(null, id)
                    if (obj is Boolean) obj = null
                    if (obj != null) L("AVMCamera.open($id) estático: ok")
                } catch (t: Throwable) { L("open($id) estático: ${err(t)}") }
                if (obj == null) {
                    val ctor = cls.getDeclaredConstructor(Int::class.javaPrimitiveType); ctor.isAccessible = true
                    obj = ctor.newInstance(id)
                    val r = findMethod(cls, "open", 0)?.invoke(obj)
                    L("new AVMCamera($id).open() = $r")
                    if (r == false) throw IllegalStateException("open() retornou false (câmera ocupada ou id inválido)")
                }
                avm = obj
                try { findMethod(cls, "setCameraFps", 1)?.invoke(obj, 25); L("setCameraFps(25) ok") } catch (t: Throwable) { L("setCameraFps: ${err(t)}") }
                // Tamanho: usa o padrão informado pelo firmware; se não houver, o legado 5120x960 (faixa panorâmica).
                var w = 0; var h = 0
                try {
                    val info = bydClass("android.hardware.BmmCameraInfo")
                    if (info != null) {
                        w = findMethod(info, "getDefaultPreviewWidth", 1)?.invoke(null, id) as? Int ?: 0
                        h = findMethod(info, "getDefaultPreviewHeight", 1)?.invoke(null, id) as? Int ?: 0
                    }
                } catch (_: Throwable) {}
                if (w <= 0 || h <= 0) { w = 5120; h = 960 }
                try { findMethod(cls, "setPreviewSize", 2)?.invoke(obj, w, h); L("setPreviewSize(${w}x$h) ok") } catch (t: Throwable) { L("setPreviewSize: ${err(t)}") }
                st.setDefaultBufferSize(w, h)

                var bound = false
                for (slot in listOf(0, 2, 1)) {
                    for (mName in listOf("addTexture", "setTexture", "setPreviewTexture")) {
                        val m = (cls.methods + cls.declaredMethods).firstOrNull { it.name == mName && it.parameterTypes.firstOrNull() == SurfaceTexture::class.java } ?: continue
                        try {
                            m.isAccessible = true
                            val r = if (m.parameterTypes.size == 2) m.invoke(obj, st, slot) else m.invoke(obj, st)
                            L("$mName(slot $slot) = $r")
                            if (r != false) bound = true
                        } catch (t: Throwable) { L("$mName(slot $slot): ${err(t)}") }
                        if (m.parameterTypes.size == 1 && bound) break
                    }
                    if (bound) break
                }
                if (!bound) L("⚠ não consegui ligar a textura — mesmo assim tentando startPreview")
                val sp = findMethod(cls, "startPreview", 0)?.invoke(obj)
                L("startPreview() = $sp → olhe o contador de quadros")
            } catch (t: Throwable) {
                L("✕ FALHOU $label: ${err(t)}")
                setStatus("$label · falhou: ${err(t)}", RED); current = ""
            }
        }.start()
    }

    private fun openCam2(id: String) {
        closeAll()
        val st = preview.surfaceTexture ?: run { L("preview ainda não está pronto"); return }
        val label = "Camera2 $id"
        startCounting(label); L("--- abrindo $label")
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            L("sem permissão CAMERA — conceda e tente de novo"); ensureCameraPermission(); current = ""; return
        }
        val th = HandlerThread("cam2").also { it.start() }; camThread = th
        val h = Handler(th.looper)
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val size = cm.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(SurfaceTexture::class.java)?.maxByOrNull { it.width * it.height }
            if (size != null) { st.setDefaultBufferSize(size.width, size.height); L("tamanho ${size.width}x${size.height}") }
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(dev: CameraDevice) {
                    cam2 = dev; L("$label aberta")
                    try {
                        val surface = Surface(st)
                        val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surface) }
                        @Suppress("DEPRECATION")
                        dev.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                cam2Session = s
                                try { s.setRepeatingRequest(req.build(), null, h); L("preview repetindo → olhe o contador") } catch (t: Throwable) { L("setRepeatingRequest: ${err(t)}") }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) { L("✕ sessão falhou") }
                        }, h)
                    } catch (t: Throwable) { L("sessão: ${err(t)}") }
                }
                override fun onDisconnected(dev: CameraDevice) { L("$label desconectada (outra função pegou a câmera?)"); dev.close() }
                override fun onError(dev: CameraDevice, error: Int) { L("✕ $label erro $error (1=em uso, 2=máx., 3=desativada, 4=dispositivo, 5=serviço)"); dev.close() }
            }, h)
        } catch (t: Throwable) { L("✕ FALHOU $label: ${err(t)}"); setStatus("$label · falhou", RED); current = "" }
    }

    private fun closeAll() {
        current = ""
        avm?.let { o ->
            val c = o.javaClass
            for (n in listOf("stopPreview", "close", "release")) try { findMethod(c, n, 0)?.invoke(o) } catch (_: Throwable) {}
            L("AVM fechada")
        }
        avm = null
        try { cam2Session?.close() } catch (_: Throwable) {}
        try { cam2?.close() } catch (_: Throwable) {}
        cam2Session = null; cam2 = null
        camThread?.quitSafely(); camThread = null
    }

    override fun onDestroy() { ui.removeCallbacks(fpsTick); closeAll(); super.onDestroy() }
}
