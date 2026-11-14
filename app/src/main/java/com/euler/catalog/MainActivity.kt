package com.euler.catalog

import android.app.Activity
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Catálogo de apps curados, com instalação silenciosa + concessão de permissões.
 * Requer que este app seja DEVICE OWNER (ver README).
 *
 * Onde colocar os APKs (uma vez, via adb):
 *   adb push waze.apk "/sdcard/Android/data/com.euler.catalog/files/apks/"
 */
class MainActivity : Activity() {

    private val ACTION_INSTALL = "com.euler.catalog.INSTALL_STATUS"
    private lateinit var dpm: DevicePolicyManager
    private lateinit var admin: ComponentName
    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        admin = ComponentName(this, CatalogAdminReceiver::class.java)

        val scroll = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        scroll.addView(container)
        setContentView(scroll)

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, IntentFilter(ACTION_INSTALL), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, IntentFilter(ACTION_INSTALL))
        }
        buildList()
    }

    private fun apkDir(): File {
        val d = File(getExternalFilesDir(null), "apks")
        if (!d.exists()) d.mkdirs()
        return d
    }

    private fun buildList() {
        container.removeAllViews()
        val isOwner = dpm.isDeviceOwnerApp(packageName)

        container.addView(TextView(this).apply {
            text = if (isOwner) "Device Owner: OK (instalação silenciosa ativa)"
                   else "ATENÇÃO: não é Device Owner — instalação vai pedir confirmação na tela e NÃO concede permissões sozinho. Rode o dpm set-device-owner (ver README)."
            setPadding(0, 0, 0, 32)
        })

        val apks = apkDir().listFiles { f -> f.name.endsWith(".apk", true) }
            ?.sortedBy { it.name } ?: emptyList()

        if (apks.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "Nenhum APK encontrado em:\n${apkDir().absolutePath}\n\n" +
                       "Envie via:\nadb push app.apk \"${apkDir().absolutePath}/\""
            })
        } else {
            for (apk in apks) {
                container.addView(Button(this).apply {
                    text = "Instalar / reinstalar: ${apk.name}"
                    setOnClickListener { install(apk) }
                })
            }
        }

        if (isOwner) {
            container.addView(TextView(this).apply { text = "\n"; setPadding(0, 40, 0, 0) })
            container.addView(Button(this).apply {
                text = "(manutenção) Liberar Device Owner"
                setOnClickListener {
                    try {
                        dpm.clearDeviceOwnerApp(packageName)
                        toast("Device Owner removido")
                        buildList()
                    } catch (e: Exception) { toast("Falha: ${e.message}") }
                }
            })
        }
    }

    private fun install(apk: File) {
        try {
            val pi = packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = pi.createSession(params)
            val session = pi.openSession(sessionId)
            apk.inputStream().use { input ->
                session.openWrite(apk.name, 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(
                this, sessionId, Intent(ACTION_INSTALL).setPackage(packageName), flags
            )
            session.commit(pending.intentSender)
            session.close()
            toast("Instalando ${apk.name}…")
        } catch (e: Exception) {
            toast("Falha ao iniciar instalação: ${e.message}")
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
                PackageInstaller.STATUS_SUCCESS -> {
                    val pkg = i.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
                    if (pkg != null) grantAll(pkg)
                    toast("Instalado: $pkg — permissões concedidas")
                }
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    // Só cai aqui se o app NÃO for Device Owner: abre o instalador normal como fallback.
                    val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33)
                        i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_INTENT)
                    confirm?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                else -> toast("Erro: ${i?.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
            }
        }
    }

    /** Concede todas as permissões de runtime que o app instalado declara. Escopo: só esse pacote. */
    private fun grantAll(pkg: String) {
        if (!dpm.isDeviceOwnerApp(packageName)) return
        val perms = try {
            packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions
        } catch (e: Exception) { null } ?: return
        for (p in perms) {
            try {
                dpm.setPermissionGrantState(admin, pkg, p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            } catch (e: Exception) { /* permissões não-runtime são ignoradas */ }
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(statusReceiver) } catch (e: Exception) {}
    }
}
