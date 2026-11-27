package com.euler.catalog

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File

/**
 * Shell com permissão de ADB, sem notebook.
 *
 * O app se conecta ao ADB do PRÓPRIO carro (127.0.0.1:5555) como cliente ADB — exatamente
 * o que outros apps de cluster fazem. Na primeira conexão o carro mostra "Permitir depuração
 * USB?"; ao tocar em "Sempre permitir", o app passa a ter shell e a chave fica autorizada.
 *
 * Tudo aqui é OPCIONAL. A loja funciona sem isto. Só é acionado quando o painel pede
 * (ex.: Setar Device Owner) ou, no futuro, para a função de cluster. Se a depuração não
 * estiver disponível (ex.: Sealion), falha de forma limpa, sem travar nada.
 */
object AdbShell {

    const val HOST = "127.0.0.1"
    const val PORT = 5555

    data class Result(val ok: Boolean, val output: String, val reason: String = "")

    private fun admin(ctx: Context) = ComponentName(ctx, CatalogAdminReceiver::class.java)

    /** true se este app já é Device Owner. Não precisa de ADB para saber. */
    fun isDeviceOwner(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager)
            .isDeviceOwnerApp(ctx.packageName)
    } catch (e: Exception) { false }

    /** Chave ADB do app (gerada uma vez e reaproveitada). É ela que o carro memoriza no "Sempre permitir". */
    private fun keyPair(ctx: Context): AdbKeyPair {
        val priv = File(ctx.filesDir, "adbkey")
        val pub = File(ctx.filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    /**
     * Roda um comando no shell do carro via ADB local.
     * O timeout é generoso de propósito: na primeira vez, dá tempo de tocar em "Sempre permitir".
     */
    fun run(ctx: Context, command: String, timeoutMs: Int = 45000): Result {
        var dadb: Dadb? = null
        return try {
            dadb = Dadb.create(HOST, PORT, keyPair(ctx), 4000, timeoutMs)
            val r = dadb.shell(command)
            val out = (r.output + r.errorOutput).trim()
            Result(r.exitCode == 0, out, if (r.exitCode == 0) "" else "exit ${r.exitCode}")
        } catch (e: Exception) {
            Result(false, "", adbError(e))
        } finally {
            try { dadb?.close() } catch (_: Exception) {}
        }
    }

    private fun adbError(e: Exception): String {
        val m = (e.message ?: e.javaClass.simpleName)
        return when {
            m.contains("refused", true) || m.contains("ECONNREFUSED", true) ->
                "ADB não está escutando na rede (depuração por TCP desligada)"
            m.contains("timeout", true) || m.contains("timed out", true) ->
                "sem resposta do ADB (autorização não concedida no carro?)"
            m.contains("reset", true) || m.contains("closed", true) ->
                "conexão ADB recusada (autorização negada?)"
            else -> m.take(160)
        }
    }

    /**
     * Define este app como Device Owner via ADB.
     * Requer depuração ligada e o carro sem contas/usuários extras.
     * Confere pelo estado real do sistema, não só pela saída do comando.
     */
    fun setDeviceOwner(ctx: Context): Result {
        if (isDeviceOwner(ctx)) return Result(true, "já era Device Owner")
        val comp = admin(ctx).flattenToShortString() // com.euler.catalog/.CatalogAdminReceiver
        val r = run(ctx, "dpm set-device-owner $comp")
        if (isDeviceOwner(ctx)) return Result(true, r.output.ifBlank { "Success" })
        // Não virou: se nem o shell respondeu, o motivo é o do ADB; senão, interpreta a saída do dpm.
        val reason = if (r.output.isBlank() && r.reason.isNotBlank()) r.reason else parseDpmError(r.output)
        return Result(false, r.output, reason)
    }

    /**
     * Re-habilita a inicialização automática deste app na whitelist do BYD, de forma silenciosa.
     * O BYD zera essa whitelist a cada instalação, então isto roda depois de cada atualização.
     * Tenta todos os caminhos que o BYD usa, numa sessão só; sucesso = o shell rodou a sequência.
     */
    fun enableAutostart(ctx: Context): Result {
        val pkg = ctx.packageName
        val cmds = listOf(
            "cmd appops set $pkg AUTO_START allow 2>/dev/null",
            "cmd appops set $pkg BOOT_COMPLETED allow 2>/dev/null",
            "appops set $pkg RUN_IN_BACKGROUND allow 2>/dev/null",
            "appops set $pkg RUN_ANY_IN_BACKGROUND allow 2>/dev/null",
            "appops set $pkg WAKE_LOCK allow 2>/dev/null",
            "dumpsys deviceidle whitelist +$pkg 2>/dev/null",
            "content call --uri content://com.byd.appstartup/whitelist --method add --arg '$pkg' 2>/dev/null",
            sscAppend("secure", pkg),
            sscAppend("global", pkg),
            "echo UBYD_OK"
        )
        val r = run(ctx, cmds.joinToString(" ; "), 20000)
        return if (r.output.contains("UBYD_OK")) Result(true, r.output) else Result(false, r.output, r.reason)
    }

    /** Acrescenta o pacote à ssc_whitelist (secure/global) sem duplicar, tratando "null". */
    private fun sscAppend(ns: String, pkg: String): String =
        "CUR=\$(settings get $ns ssc_whitelist); [ \"\$CUR\" = null ] && CUR=; case \",\$CUR,\" in *,$pkg,*) ;; *) settings put $ns ssc_whitelist \"\${CUR:+\$CUR,}$pkg\" ;; esac 2>/dev/null"

    /**
     * Liga, em silêncio, o serviço de acessibilidade do Unlock BYD (usado pra religar o início
     * automático após updates). Só funciona com shell (WRITE_SECURE_SETTINGS). Idempotente.
     */
    fun enableAccessibilityService(ctx: Context): Result {
        val comp = ComponentName(ctx, AutostartService::class.java).flattenToString() // com.euler.catalog/com.euler.catalog.AutostartService
        val cmds = listOf(
            "CUR=\$(settings get secure enabled_accessibility_services); [ \"\$CUR\" = null ] && CUR=; case \":\$CUR:\" in *:$comp:*) ;; *) settings put secure enabled_accessibility_services \"\${CUR:+\$CUR:}$comp\" ;; esac 2>/dev/null",
            "settings put secure accessibility_enabled 1 2>/dev/null",
            "echo UBYD_OK"
        )
        val r = run(ctx, cmds.joinToString(" ; "), 15000)
        return if (r.output.contains("UBYD_OK")) Result(true, r.output) else Result(false, r.output, r.reason)
    }

    /**
     * O serviço está marcado como ligado nas configurações mas o Android não o reconectou (comum logo
     * após uma atualização): tira e recoloca na lista, o que força o sistema a reconectar.
     */
    fun rebindAccessibilityService(ctx: Context): Result {
        val comp = ComponentName(ctx, AutostartService::class.java).flattenToString()
        val short = ComponentName(ctx, AutostartService::class.java).flattenToShortString()
        val cmds = listOf(
            "CUR=\$(settings get secure enabled_accessibility_services); [ \"\$CUR\" = null ] && CUR=",
            "REST=\$(echo \"\$CUR\" | tr ':' '\\n' | grep -v -x -e '$comp' -e '$short' | grep -v '^\$' | paste -sd ':' -)",
            "settings put secure enabled_accessibility_services \"\$REST\"",
            "sleep 1",
            "settings put secure enabled_accessibility_services \"\${REST:+\$REST:}$comp\"",
            "settings put secure accessibility_enabled 1",
            "echo UBYD_OK"
        )
        val r = run(ctx, cmds.joinToString(" ; "), 20000)
        return if (r.output.contains("UBYD_OK")) Result(true, r.output) else Result(false, r.output, r.reason)
    }

    // ---------- VIGIA (carro desligado) — teste v70 ----------
    private const val VIGIA_LOG = "/data/local/tmp/ubyd_vigia.log"
    private const val VIGIA_CLS = "com.euler.catalog.AccDaemon"
    // Padrão para pkill/pgrep que NÃO casa com o próprio comando do shell (senão o pkill mata o shell que o roda).
    private const val VIGIA_PAT = "'[A]ccDaemon'"

    /** Inicia o processo que segura a multimídia acordada por [minutes] minutos (roda como shell, fora do app). */
    fun startVigia(ctx: Context, minutes: Int, heartbeat: String = ""): Result {
        val apk = ctx.applicationInfo.sourceDir
        val cmd = "pkill -f $VIGIA_PAT 2>/dev/null; rm -f $VIGIA_LOG; " +
            "C=com.euler.catalog.Acc; (CLASSPATH=$apk setsid nohup app_process /system/bin ${'$'}{C}Daemon $minutes $heartbeat >/dev/null 2>&1 &) ; " +
            "sleep 3; cat $VIGIA_LOG 2>/dev/null; pgrep -f $VIGIA_PAT >/dev/null && echo UBYD_RUNNING; echo UBYD_OK"
        val r = run(ctx, cmd, 20000)
        return if (r.output.contains("UBYD_RUNNING")) Result(true, r.output.replace("UBYD_RUNNING", "").replace("UBYD_OK", "").trim())
        else Result(false, r.output.replace("UBYD_OK", "").trim(), r.reason)
    }

    fun stopVigia(ctx: Context): Result {
        val r = run(ctx, "pkill -f $VIGIA_PAT 2>/dev/null; sleep 1; pgrep -f $VIGIA_PAT >/dev/null && echo AINDA_RODANDO || echo parada; tail -3 $VIGIA_LOG 2>/dev/null", 15000)
        return Result(!r.output.contains("AINDA_RODANDO"), r.output.trim(), r.reason)
    }

    /** Controles do carro (v73): roda CarCtl como shell e devolve a linha de resultado. */
    fun carCtl(ctx: Context, args: String): Result {
        val cmd = args.trim().substringBefore(' ')
        if (cmd !in CarCtl.SHELL_CMDS) {
            // Ar/janelas: no próprio app. Se faltar permissão, concede pelo ADB e tenta de novo.
            var out = CarCtl.local(ctx, args)
            if (out.contains("aparelho não abriu") || out.contains("Security")) {
                run(ctx, CarCtl.PERMS.joinToString("; ") { "pm grant ${ctx.packageName} android.permission.$it 2>/dev/null" } + "; echo ok", 15000)
                out = CarCtl.local(ctx, args)
            }
            val bad = out.startsWith("ERRO") || out.contains("aparelho não abriu") || out.contains("não existe") || out.contains("Exception")
            return Result(!bad, out)
        }
        val apk = ctx.applicationInfo.sourceDir
        val r = run(ctx, "CLASSPATH=$apk app_process /system/bin com.euler.catalog.CarCtl $args 2>&1 | tail -15", 25000)
        val line = r.output.lines().firstOrNull { it.startsWith("UBYD_RES ") }?.removePrefix("UBYD_RES ")
        return if (line != null) Result(!line.startsWith("ERRO"), line) else Result(false, r.output.trim(), r.reason)
    }

    /** true = processo da vigia vivo; null = não deu pra perguntar (sem ADB). */
    fun vigiaRunning(ctx: Context): Boolean? {
        val r = run(ctx, "pgrep -f $VIGIA_PAT >/dev/null && echo UBYD_SIM || echo UBYD_NAO", 12000)
        return when { r.output.contains("UBYD_SIM") -> true; r.output.contains("UBYD_NAO") -> false; else -> null }
    }

    fun vigiaStatus(ctx: Context): Result {
        val r = run(ctx, "pgrep -f $VIGIA_PAT >/dev/null && echo '● RODANDO' || echo '○ PARADA'; tail -12 $VIGIA_LOG 2>/dev/null", 15000)
        return Result(true, r.output.trim(), r.reason)
    }

    /** Abre a tela de início automático da BYD pelo shell (fallback quando o Context não consegue). */
    fun openAutostartPage(ctx: Context): Result {
        val cmds = listOf(
            "am start -n com.byd.appstartmanagement/.frame.AppStartManagement 2>/dev/null",
            "am start -n com.byd.appstartmanagement/.AppStartManagement 2>/dev/null",
            "am start -n com.byd.setting/.activity.AutoStartActivity 2>/dev/null",
            "echo UBYD_OK"
        )
        return run(ctx, cmds.joinToString(" ; "), 12000)
    }

    /** Lê o estado atual do início automático (pra diagnóstico). */
    fun autostartState(ctx: Context): String {
        val pkg = ctx.packageName
        val cmd = "echo 'appops AUTO_START:'; cmd appops get $pkg AUTO_START 2>&1; " +
                  "echo 'appops BOOT_COMPLETED:'; cmd appops get $pkg BOOT_COMPLETED 2>&1; " +
                  "echo 'ssc_whitelist(secure):'; settings get secure ssc_whitelist 2>&1; " +
                  "echo 'ssc_whitelist(global):'; settings get global ssc_whitelist 2>&1; " +
                  "echo 'byd whitelist:'; content query --uri content://com.byd.appstartup/whitelist 2>&1 | head -40; " +
                  "echo 'a11y enabled:'; settings get secure enabled_accessibility_services 2>&1"
        return run(ctx, cmd, 15000).output
    }

    /** Concede a permissão de "sobrepor outras telas" a este app, silenciosamente, via ADB. */
    fun grantOverlay(ctx: Context): Result {
        val pkg = ctx.packageName
        return run(ctx, "appops set $pkg SYSTEM_ALERT_WINDOW allow 2>/dev/null ; echo UBYD_OK", 15000)
            .let { if (it.output.contains("UBYD_OK")) Result(true, it.output) else it }
    }

    /** Esconde as barras superior e inferior da multimídia (modo imersivo). Precisa de shell. */
    fun hideBars(ctx: Context): Result {
        val r = run(ctx, "settings put global policy_control immersive.full=* 2>&1 ; echo UBYD_OK", 10000)
        return if (r.output.contains("UBYD_OK") && !r.output.contains("Error", true) && !r.output.contains("Exception", true))
            Result(true, r.output) else Result(false, r.output, r.reason.ifBlank { r.output.take(120) })
    }

    /** Restaura as barras superior e inferior. */
    fun showBars(ctx: Context): Result {
        val r = run(ctx, "settings put global policy_control null 2>&1 ; echo UBYD_OK", 10000)
        return if (r.output.contains("UBYD_OK") && !r.output.contains("Error", true) && !r.output.contains("Exception", true))
            Result(true, r.output) else Result(false, r.output, r.reason.ifBlank { r.output.take(120) })
    }

    /** Libera a permissão de instalar apps (REQUEST_INSTALL_PACKAGES) para um pacote, via shell.
     *  Usado nos carros com sistema Google, onde não há Device Owner pra conceder isso. */
    fun grantInstallPermission(ctx: Context, pkg: String): Result {
        val cmd =
            "appops set $pkg REQUEST_INSTALL_PACKAGES allow 2>&1; " +
            "cmd appops set $pkg REQUEST_INSTALL_PACKAGES allow 2>/dev/null; " +
            "echo UBYD_OK"
        val r = run(ctx, cmd, 15000)
        return if (r.output.contains("UBYD_OK") && !r.output.contains("Exception", true))
            Result(true, r.output) else Result(false, r.output, r.reason.ifBlank { r.output.take(120) })
    }

    /** Injeta os 3 ajustes de janela flutuante (freeform) que o Taskbar precisa p/ vídeo em movimento. */
    fun enableTaskbarFreeform(ctx: Context): Result {
        val cmd =
            "settings put global enable_freeform_support 1 2>&1; " +
            "settings put global force_resizable_activities 1 2>&1; " +
            "settings put secure freeform_window_management 1 2>&1; " +
            "echo UBYD_OK"
        val r = run(ctx, cmd, 15000)
        return if (r.output.contains("UBYD_OK") && !r.output.contains("Exception", true))
            Result(true, r.output) else Result(false, r.output, r.reason.ifBlank { r.output.take(120) })
    }

    /** Descobre o launcher (app da home) e copia o(s) APK(s) dele para /sdcard/Download. */
    fun extractLauncher(ctx: Context): Result {
        val cmd =
            "L=\$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null | grep / | head -1); " +
            "PKG=\${L%%/*}; " +
            "if [ -z \"\$PKG\" ]; then echo 'NAO_ACHEI_LAUNCHER'; else " +
            "mkdir -p /sdcard/Download 2>/dev/null; i=0; " +
            "for p in \$(pm path \"\$PKG\" | sed 's/package://'); do " +
            "if [ \$i -eq 0 ]; then cp \"\$p\" \"/sdcard/Download/launcher_\$PKG.apk\" 2>&1; " +
            "else cp \"\$p\" \"/sdcard/Download/launcher_\${PKG}_\$i.apk\" 2>&1; fi; i=\$((i+1)); done; " +
            "echo \"Launcher: \$PKG\"; echo 'Arquivos em Download:'; ls -la /sdcard/Download/launcher_* 2>&1; fi"
        return run(ctx, cmd, 25000)
    }

    /** Copia os APKs dos apps do cluster (containerservice + clusterdebug) para /sdcard/Download. */
    fun extractClusterApks(ctx: Context): Result {
        val cmd =
            "mkdir -p /sdcard/Download 2>/dev/null; " +
            "for PKG in com.xdja.containerservice com.byd.clusterdebug; do " +
            "P=\$(pm path \"\$PKG\" 2>/dev/null | sed 's/package://' | head -1); " +
            "if [ -z \"\$P\" ]; then echo \"\$PKG: NAO INSTALADO\"; else " +
            "i=0; for p in \$(pm path \"\$PKG\" | sed 's/package://'); do " +
            "if [ \$i -eq 0 ]; then cp \"\$p\" \"/sdcard/Download/cluster_\$PKG.apk\" 2>&1; " +
            "else cp \"\$p\" \"/sdcard/Download/cluster_\${PKG}_\$i.apk\" 2>&1; fi; i=\$((i+1)); done; " +
            "echo \"\$PKG: ok\"; fi; done; " +
            "echo 'Arquivos em Download:'; ls -la /sdcard/Download/cluster_* 2>&1"
        return run(ctx, cmd, 30000)
    }

    private fun parseDpmError(out: String): String = when {
        out.contains("already", true) && out.contains("owner", true) -> "já existe um owner/admin definido"
        out.contains("account", true) -> "há conta(s) cadastradas no carro — remova antes"
        out.contains("users", true) -> "há mais de um usuário no carro"
        out.isBlank() -> "falha ao definir Device Owner"
        else -> out.take(160)
    }
}
