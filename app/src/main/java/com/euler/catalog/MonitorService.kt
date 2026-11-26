package com.euler.catalog

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Câmeras ao vivo (v60): mantém o carro conectado ao hub do servidor (/ws/car) enquanto o
 * monitoramento estiver liberado no painel. A câmera SÓ liga quando um celular vinculado pede
 * (mensagem 'start') e desliga quando ele sai. Conexão parada é leve (um "ping" a cada 50 s).
 */
class MonitorService : Service() {

    companion object {
        private const val TAG = "MonitorService"
        @Volatile var connected = false; private set

        /** Chamado a cada /api/register: liga ou desliga conforme o painel. */
        fun sync(ctx: Context, on: Boolean) {
            val app = ctx.applicationContext
            val prefs = app.getSharedPreferences("monitor", Context.MODE_PRIVATE)
            val was = prefs.getBoolean("on", false)
            if (was != on) prefs.edit().putBoolean("on", on).apply()
            try {
                val i = Intent(app, MonitorService::class.java)
                if (on) { if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i) }
                else if (was) app.stopService(i)
            } catch (e: Exception) { Log.w(TAG, "sync: ${e.message}") }
        }
        fun enabled(ctx: Context) = ctx.applicationContext.getSharedPreferences("monitor", Context.MODE_PRIVATE).getBoolean("on", false)
    }

    private val ui = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).connectTimeout(15, TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null
    private var backoff = 5_000L
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundSafe()
        running = true
        connect()
        ui.postDelayed(pinger, 50_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ws == null) connect()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        ui.removeCallbacksAndMessages(null)
        try { ws?.close(1000, "off") } catch (_: Exception) {}
        ws = null; connected = false
        LiveCam.stopAll()
        super.onDestroy()
    }

    private val pinger = object : Runnable {
        override fun run() {
            try { ws?.send("ping") } catch (_: Exception) {}
            if (running) ui.postDelayed(this, 50_000)
        }
    }

    private fun connect() {
        if (!running) return
        val url = Store.SERVER.replaceFirst("https://", "wss://") + "/ws/car?id=" + Store.carId(this)
        ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true; backoff = 5_000L
                Log.i(TAG, "conectado ao hub")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text == "pong") return
                val m = try { JSONObject(text) } catch (_: Exception) { return }
                val v = m.optString("v", "")
                val send: (JSONObject) -> Unit = { o -> try { webSocket.send(o.toString()) } catch (_: Exception) {} }
                when (m.optString("t")) {
                    "start" -> if (v.isNotEmpty()) LiveCam.start(applicationContext, v, m.optJSONArray("ice"), m.optString("cam", "auto"), m.optString("size", "auto"), send)
                    "cam" -> if (v.isNotEmpty()) LiveCam.switchCam(v, m.optString("cam", "auto"), m.optString("size", "auto"))
                    "answer" -> if (v.isNotEmpty()) LiveCam.answer(v, m.optString("sdp"))
                    "stop" -> if (v.isNotEmpty()) LiveCam.stop(v)
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { dropped(webSocket, "fechado $code $reason") }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // 403 = monitoramento desligado/carro inativo no painel: não insiste, o register desliga o serviço.
                dropped(webSocket, "falha ${response?.code ?: ""} ${t.message}")
                if (response?.code == 403) backoff = 10 * 60_000L
            }
        })
    }

    private fun dropped(w: WebSocket, why: String) {
        if (w !== ws) return
        Log.w(TAG, "hub: $why")
        connected = false; ws = null
        LiveCam.stopAll()
        if (!running) return
        val wait = backoff
        backoff = (backoff * 2).coerceAtMost(5 * 60_000L)
        ui.postDelayed({ if (running && ws == null) connect() }, wait)
    }

    private fun startForegroundSafe() {
        try {
            val ch = "ubyd_monitor"
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(NotificationChannel(ch, "Monitoramento", NotificationManager.IMPORTANCE_MIN))
            }
            val n: Notification = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, ch) else @Suppress("DEPRECATION") Notification.Builder(this))
                .setContentTitle("Monitoramento disponível")
                .setSmallIcon(R.mipmap.ic_launcher)
                .build()
            if (Build.VERSION.SDK_INT >= 34) startForeground(43, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(43, n)
        } catch (e: Exception) {}
    }
}
