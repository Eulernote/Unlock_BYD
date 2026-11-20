package com.euler.catalog

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast

/**
 * Ícone flutuante sobre a tela. Um toque joga o que está na tela pro cluster; outro toque traz de volta.
 * Pode ser arrastado pela tela. Só roda enquanto o cluster estiver habilitado nas configurações.
 */
class ClusterOverlayService : Service() {

    private lateinit var wm: WindowManager
    private var icon: TextView? = null
    private var gestureView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundSafe()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        syncOverlays()
    }

    /** Mostra ícone sempre (também serve de escape); adiciona/remove o overlay de gesto pelo modo. */
    private fun syncOverlays() {
        addIcon()
        if (Store.clusterMode(applicationContext) == "gesture4") addGestureOverlay() else removeGesture()
    }

    private fun startForegroundSafe() {
        try {
            val ch = "ubyd_cluster"
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(NotificationChannel(ch, "Cluster", NotificationManager.IMPORTANCE_MIN))
            }
            val n: Notification = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, ch) else @Suppress("DEPRECATION") Notification.Builder(this))
                .setContentTitle("Projeção no cluster ativa")
                .setSmallIcon(R.mipmap.ic_launcher)
                .build()
            if (Build.VERSION.SDK_INT >= 34) startForeground(42, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(42, n)
        } catch (e: Exception) {}
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun addIcon() {
        if (icon != null) return
        val tv = TextView(this).apply {
            text = "⤢"
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            val bg = GradientDrawable(); bg.setColor(0xCC2BB0A6.toInt()); bg.cornerRadius = dp(28).toFloat()
            background = bg
            val pad = dp(14); setPadding(pad, pad, pad, pad)
        }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                   else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(16); y = dp(120) }

        var downX = 0f; var downY = 0f; var px = 0; var py = 0; var moved = false; var downTime = 0L
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; px = lp.x; py = lp.y; moved = false; downTime = System.currentTimeMillis(); true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                    if (kotlin.math.abs(dx) > dp(6) || kotlin.math.abs(dy) > dp(6)) moved = true
                    lp.x = px + dx; lp.y = py + dy; try { wm.updateViewLayout(tv, lp) } catch (_: Exception) {}; true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        // Segurar (>600ms) desliga o modo de gesto (escape, pra não travar a tela).
                        if (System.currentTimeMillis() - downTime > 600 && Store.clusterMode(applicationContext) == "gesture4") {
                            Store.setClusterMode(applicationContext, "icon"); removeGesture(); toast("Modo 4 dedos desligado")
                        } else onTap()
                    }
                    true
                }
                else -> false
            }
        }
        try { wm.addView(tv, lp); icon = tv; params = lp } catch (e: Exception) { stopSelf() }
    }

    // ---- gesto de 4 dedos na tela toda (modo de teste) ----

    private fun addGestureOverlay() {
        if (gestureView != null) return
        val v = View(this)
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                   else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        var startX = 0f; var fourSeen = false
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { fourSeen = false }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (e.pointerCount >= 4 && !fourSeen) { fourSeen = true; startX = avgX(e) }
                }
                MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (fourSeen) {
                        val dx = avgX(e) - startX
                        if (kotlin.math.abs(dx) > dp(90)) { if (dx < 0) fire(true) else fire(false); fourSeen = false }
                        if (e.actionMasked != MotionEvent.ACTION_POINTER_UP) fourSeen = false
                    }
                }
            }
            true   // precisa consumir pra conseguir contar os 4 dedos (bloqueia 1 dedo nesse modo)
        }
        try { wm.addView(v, lp); gestureView = v } catch (e: Exception) {}
    }

    private fun removeGesture() {
        try { gestureView?.let { wm.removeView(it) } } catch (e: Exception) {}
        gestureView = null
    }

    private fun avgX(e: MotionEvent): Float {
        var s = 0f; val n = e.pointerCount; if (n == 0) return 0f
        for (i in 0 until n) s += e.getX(i); return s / n
    }

    /** toCluster=true: 4 dedos pra esquerda → envia; false: pra direita → traz de volta. */
    private fun fire(toCluster: Boolean) {
        if (busy) return
        busy = true
        toast(if (toCluster) "4 dedos → cluster" else "4 dedos → tela")
        Thread {
            val r = if (toCluster) ClusterCast.throwToCluster(applicationContext) else ClusterCast.bringBack(applicationContext)
            setIconState(ClusterCast.isCasting()); toast(r.detail); busy = false
        }.start()
    }

    private fun setIconState(casting: Boolean) {
        ui.post {
            icon?.let {
                it.text = if (casting) "⎚" else "⤢"
                (it.background as? GradientDrawable)?.setColor(if (casting) 0xCC27C08A.toInt() else 0xCC2BB0A6.toInt())
            }
        }
    }

    private fun onTap() {
        if (busy) return
        busy = true
        val goingToCast = !ClusterCast.isCasting()
        toast(if (goingToCast) "Enviando pro cluster…" else "Trazendo de volta…")
        Thread {
            val r = if (goingToCast) ClusterCast.throwToCluster(applicationContext) else ClusterCast.bringBack(applicationContext)
            setIconState(ClusterCast.isCasting())
            toast(r.detail)
            busy = false
        }.start()
    }

    private fun toast(m: String) = ui.post { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { syncOverlays(); return START_STICKY }

    override fun onDestroy() {
        super.onDestroy()
        try { icon?.let { wm.removeView(it) } } catch (e: Exception) {}
        removeGesture()
        icon = null
    }

    companion object {
        fun start(ctx: Context) {
            try {
                val i = Intent(ctx, ClusterOverlayService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Exception) {}
        }
        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, ClusterOverlayService::class.java)) } catch (e: Exception) {}
        }
    }
}
