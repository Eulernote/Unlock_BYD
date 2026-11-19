package com.euler.catalog

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display

/**
 * Projeção de apps no cluster do BYD, via shell ADB local (AdbShell).
 *
 * Fluxo (confirmado no app de referência):
 *   1. Abre a superfície do cluster:  service call AutoContainer 2 i32 1000 i32 <código> s16 ""
 *      (tenta "AutoContainer" e "auto_container", códigos 18 e 16; se nada, sobe o xdja containerservice).
 *   2. Acha o display do cluster: um display cujo nome tem "fission"/"xdjascreenprojection"/"cluster".
 *   3. Joga o app em primeiro plano pra lá: am stack move-task <taskId> <display> true
 *      (preserva o app rodando; fallback: am start --display <id> relançando o app).
 *   4. Volta: move a tarefa de volta pro display 0 e libera o cluster (código 0).
 *
 * O código certo (16/18) e o nome do display variam por firmware — por isso há diagnose(),
 * que lista tudo pra confirmarmos no primeiro teste no carro.
 */
object ClusterCast {

    // Códigos de abertura: 18 (DiLink antigo) e 16 (DiLink 5). Tentamos os dois.
    private val SERVICES = listOf("AutoContainer", "auto_container")
    private val OPEN_CODES = listOf(18, 16)

    data class Result(val ok: Boolean, val detail: String)

    /** Última tarefa jogada no cluster, pra conseguir trazer de volta. */
    @Volatile var lastTaskId: Int = -1
    @Volatile var lastPkg: String = ""

    // ---------- superfície do cluster ----------

    /** Abre a superfície do cluster. Retorna o método que respondeu (ou vazio se nenhum). */
    private fun openSurface(ctx: Context): String {
        for (svc in SERVICES) for (code in OPEN_CODES) {
            val r = AdbShell.run(ctx, "service call $svc 2 i32 1000 i32 $code s16 '' 2>/dev/null", 8000)
            if (r.ok && !r.output.contains("does not exist", true) && !r.output.contains("not found", true)
                && !r.output.contains("Service $svc", true)) {
                // service call devolve "Result: Parcel(...)" quando o serviço existe
                if (r.output.contains("Result", true) || r.output.contains("Parcel", true) || r.output.isBlank())
                    return "$svc/$code"
            }
        }
        // Sem serviço: tenta subir o containerservice do fornecedor.
        AdbShell.run(ctx, "am startservice --user 0 -n com.xdja.containerservice/.AutoDisplayService 2>/dev/null", 8000)
        AdbShell.run(ctx, "am startservice --user 0 -n com.byd.containerservice/.AutoDisplayService 2>/dev/null", 6000)
        return ""
    }

    /** Fecha/libera a superfície do cluster (código 0). */
    private fun releaseSurface(ctx: Context) {
        for (svc in SERVICES) AdbShell.run(ctx, "service call $svc 2 i32 1000 i32 0 s16 '' 2>/dev/null", 6000)
    }

    // ---------- display do cluster ----------

    /** Nomes que indicam o display do cluster. */
    private fun isClusterName(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("fission") || n.contains("xdjascreenprojection") || n.contains("cluster") || n.contains("instrument")
    }

    /** Procura o display do cluster pelo DisplayManager (não precisa de ADB). -1 se não achar. */
    fun clusterDisplayId(ctx: Context): Int {
        return try {
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val match = dm.displays.filter { isClusterName(it.name) }
            // Prefere um display secundário (id > 0).
            (match.firstOrNull { it.displayId > 0 } ?: match.firstOrNull())?.displayId ?: -1
        } catch (e: Exception) { -1 }
    }

    /** Lista dos displays (id + nome), pro diagnóstico. */
    private fun displaysText(ctx: Context): String {
        return try {
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            dm.displays.joinToString("\n") { "  #${it.displayId} ${it.name}" }
        } catch (e: Exception) { "  (erro ao listar displays: ${e.message})" }
    }

    // ---------- app/tarefa em primeiro plano ----------

    /** (taskId, "pacote/atividade") do app em primeiro plano, ou null. Ignora o próprio Unlock BYD. */
    private fun foreground(ctx: Context): Pair<Int, String>? {
        val r = AdbShell.run(ctx, "dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity|ResumedActivity' 2>/dev/null", 8000)
        val out = r.output
        if (out.isBlank()) return null
        // Linha típica: "...ResumedActivity: ActivityRecord{abc u0 com.google.android.youtube/.UrlActivity t123}"
        val comp = Regex("""([a-zA-Z0-9_.]+/[a-zA-Z0-9_.$]+)""").find(out)?.groupValues?.getOrNull(1) ?: return null
        if (comp.startsWith(ctx.packageName)) return null
        val taskId = Regex("""\bt(\d+)""").find(out)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
        return Pair(taskId, comp)
    }

    // ---------- ações ----------

    /** Joga o app que está na tela pro cluster. Abre a superfície, acha o display e move a tarefa. */
    fun throwToCluster(ctx: Context): Result {
        val method = openSurface(ctx)
        // O display do cluster costuma aparecer só depois de abrir a superfície: espera um pouco.
        var id = -1
        val until = System.currentTimeMillis() + 4000
        while (System.currentTimeMillis() < until) { id = clusterDisplayId(ctx); if (id > 0) break; Thread.sleep(250) }
        if (id < 0) { releaseSurface(ctx); return Result(false, "não achei o display do cluster (abertura: ${method.ifEmpty { "nenhuma respondeu" }})") }

        val fg = foreground(ctx) ?: return Result(false, "não identifiquei o app em primeiro plano")
        val (taskId, comp) = fg
        if (taskId > 0) {
            val r = AdbShell.run(ctx, "am stack move-task $taskId $id true 2>/dev/null || cmd activity move-task $taskId $id true 2>/dev/null", 8000)
            if (r.ok) { lastTaskId = taskId; lastPkg = comp.substringBefore('/'); return Result(true, "$comp → cluster #$id") }
        }
        // Fallback: relança o app no display do cluster.
        val r2 = AdbShell.run(ctx, "am start --user 0 --display $id --windowingMode 1 -f 0x10000000 --ez cluster true -n $comp 2>/dev/null", 8000)
        return if (r2.ok) { lastTaskId = taskId; lastPkg = comp.substringBefore('/'); Result(true, "$comp → cluster #$id (relançado)") }
        else Result(false, "falhei ao mover pro cluster: ${r2.reason.ifBlank { r2.output.take(120) }}")
    }

    /** Traz de volta pra tela principal o que foi jogado no cluster, e libera a superfície. */
    fun bringBack(ctx: Context): Result {
        var moved = false
        if (lastTaskId > 0) {
            val r = AdbShell.run(ctx, "am stack move-task $lastTaskId 0 true 2>/dev/null || cmd activity move-task $lastTaskId 0 true 2>/dev/null", 8000)
            moved = r.ok
        } else if (lastPkg.isNotEmpty()) {
            val r = AdbShell.run(ctx, "am start --user 0 --display 0 -f 0x10000000 $lastPkg 2>/dev/null", 8000)
            moved = r.ok
        }
        releaseSurface(ctx)
        lastTaskId = -1; lastPkg = ""
        return Result(moved, if (moved) "voltou pra tela do carro" else "liberei o cluster")
    }

    /** true se há algo jogado no cluster agora (controle do ícone: jogar x voltar). */
    fun isCasting(): Boolean = lastTaskId > 0 || lastPkg.isNotEmpty()

    // ---------- diagnóstico (primeiro teste no carro) ----------

    /** Relatório pra confirmar, no carro, o código de abertura e o display certos. */
    fun diagnose(ctx: Context): String {
        val sb = StringBuilder()
        sb.append("Displays antes de abrir:\n").append(displaysText(ctx)).append("\n\n")
        sb.append("Testando abertura da superfície:\n")
        for (svc in SERVICES) for (code in OPEN_CODES) {
            val r = AdbShell.run(ctx, "service call $svc 2 i32 1000 i32 $code s16 '' 2>&1", 6000)
            val resumo = r.output.replace("\n", " ").take(80).ifBlank { r.reason }
            sb.append("  $svc code $code → ${if (r.ok) "ok" else "falhou"}: $resumo\n")
        }
        Thread.sleep(800)
        sb.append("\nDisplays depois de abrir:\n").append(displaysText(ctx)).append("\n")
        val id = clusterDisplayId(ctx)
        sb.append("\nDisplay do cluster detectado: ").append(if (id >= 0) "#$id" else "nenhum").append("\n")
        val fg = foreground(ctx)
        sb.append("App em primeiro plano: ").append(fg?.second ?: "não identificado").append(fg?.let { " (task ${it.first})" } ?: "").append("\n")
        releaseSurface(ctx)
        return sb.toString()
    }
}
