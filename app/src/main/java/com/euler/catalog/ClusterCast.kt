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
    // Código 46 é o que liga a projeção no v33 (DiLink5); 18/16 cobrem firmwares mais antigos.
    private val OPEN_CODES = listOf(46, 18, 16)

    data class Result(val ok: Boolean, val detail: String)

    /** Última tarefa jogada no cluster, pra conseguir trazer de volta. */
    @Volatile var lastTaskId: Int = -1
    @Volatile var lastPkg: String = ""

    // ---------- superfície do cluster ----------

    /** Abre a superfície do cluster. Retorna o método que respondeu (ou vazio se nenhum). */
    private fun openSurface(ctx: Context): String {
        // No v33 (DiLink5) o serviço de debug do cluster ajuda a criar a superfície de projeção.
        AdbShell.run(ctx, "am startservice -n com.byd.clusterdebug/.ClusterDebugService 2>/dev/null", 6000)
        for (svc in SERVICES) for (code in OPEN_CODES) {
            // s16 null (a string "null") — é o que o app de referência usa no v33.
            val r = AdbShell.run(ctx, "service call $svc 2 i32 1000 i32 $code s16 null 2>/dev/null", 8000)
            if (r.ok && !r.output.contains("does not exist", true) && !r.output.contains("not found", true)
                && !r.output.contains("Service $svc", true)) {
                if (r.output.contains("Parcel(00000000", true) || r.output.contains("Result", true) || r.output.isBlank())
                    return "$svc/$code"
            }
        }
        // Sem serviço: tenta subir o containerservice do fornecedor.
        AdbShell.run(ctx, "am startservice --user 0 -n com.xdja.containerservice/.AutoDisplayService 2>/dev/null", 6000)
        return ""
    }

    /** Fecha/libera a superfície do cluster (sequência 46 → 18 → 0, como o app de referência). */
    private fun releaseSurface(ctx: Context) {
        for (svc in SERVICES) AdbShell.run(ctx, "service call $svc 2 i32 1000 i32 46 s16 null 2>/dev/null; sleep 0.3; service call $svc 2 i32 1000 i32 18 s16 null 2>/dev/null; sleep 0.3; service call $svc 2 i32 1000 i32 0 s16 null 2>/dev/null", 8000)
    }

    // ---------- display do cluster ----------

    /** Nomes que indicam o display do cluster. */
    private fun isClusterName(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("fission") || n.contains("xdja") || n.contains("virtualsurface") ||
               n.contains("cluster") || n.contains("instrument")
    }

    /** Procura o display do cluster pelo DisplayManager (não precisa de ADB). -1 se não achar. */
    fun clusterDisplayId(ctx: Context): Int {
        // 1) DisplayManager, categoria PRESENTATION (é onde o cluster virtual do v33 aparece).
        try {
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val lists = listOf(
                dm.getDisplays("android.hardware.display.category.PRESENTATION"),
                dm.displays
            )
            for (arr in lists) {
                val match = arr.filter { it.displayId > 0 && isClusterName(it.name) }
                if (match.isNotEmpty()) return match.first().displayId
            }
            // Qualquer display secundário na categoria PRESENTATION serve.
            dm.getDisplays("android.hardware.display.category.PRESENTATION").firstOrNull { it.displayId > 0 }?.let { return it.displayId }
        } catch (e: Exception) {}
        // 2) Shell dumpsys: enxerga displays PRIVADOS (o cluster do BYD costuma ser privado).
        return clusterDisplayIdShell(ctx)
    }

    /** Lê os displays pelo shell (dumpsys), que vê o display privado do cluster. -1 se não achar. */
    private fun clusterDisplayIdShell(ctx: Context): Int {
        // Extrai pares (displayId, nome) do dumpsys display.
        val r = AdbShell.run(ctx, "dumpsys display | grep -oE 'displayId [0-9]+[^,}]*' | head -40", 8000)
        val ids = mutableListOf<Pair<Int, String>>()
        for (line in r.output.lines()) {
            val m = Regex("""displayId\s+(\d+)(.*)""").find(line) ?: continue
            val id = m.groupValues[1].toIntOrNull() ?: continue
            ids.add(id to line.lowercase())
        }
        // Preferência: um display cujo nome/linha pareça de cluster.
        ids.firstOrNull { isClusterName(it.second) }?.let { return it.first }
        // Senão: o menor display id > 0 (o cluster costuma ser o display 1/2).
        return ids.map { it.first }.filter { it > 0 }.minOrNull() ?: -1
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
        // Vários formatos conforme o firmware (DiLink 3/4/5).
        val cmds = listOf(
            "dumpsys activity activities | grep -E 'ResumedActivity|topResumedActivity|mResumedActivity'",
            "dumpsys activity activities | grep -E 'mFocusedApp|ResumedActivity'",
            "dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'",
            "dumpsys activity top | grep -E 'ACTIVITY '"
        )
        for (c in cmds) {
            val out = AdbShell.run(ctx, "$c 2>/dev/null", 8000).output
            if (out.isBlank()) continue
            val comp = Regex("""([a-zA-Z0-9_.]+/[a-zA-Z0-9_.$]+)""").find(out)?.groupValues?.getOrNull(1) ?: continue
            if (comp.startsWith(ctx.packageName)) continue
            val taskId = Regex("""\bt(\d+)""").find(out)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: -1
            return Pair(taskId, comp)
        }
        return null
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
        AdbShell.run(ctx, "am startservice -n com.byd.clusterdebug/.ClusterDebugService 2>/dev/null", 6000)
        sb.append("Testando abertura da superfície (s16 null):\n")
        for (svc in SERVICES) for (code in OPEN_CODES) {
            val r = AdbShell.run(ctx, "service call $svc 2 i32 1000 i32 $code s16 null 2>&1", 6000)
            val resumo = r.output.replace("\n", " ").take(80).ifBlank { r.reason }
            sb.append("  $svc code $code → ${if (r.ok) "ok" else "falhou"}: $resumo\n")
        }
        Thread.sleep(1500)
        sb.append("\nDisplays (app) depois de abrir:\n").append(displaysText(ctx)).append("\n")
        // Categoria PRESENTATION: é onde o cluster virtual do v33 aparece.
        sb.append("\nDisplays PRESENTATION (onde o cluster do v33 aparece):\n")
        try {
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val pres = dm.getDisplays("android.hardware.display.category.PRESENTATION")
            sb.append(if (pres.isEmpty()) "  (nenhum)\n" else pres.joinToString("\n") { "  #${it.displayId} ${it.name}" } + "\n")
        } catch (e: Exception) { sb.append("  (erro: ${e.message})\n") }

        // Lista crua pelo shell: enxerga displays privados (o cluster).
        sb.append("\n[shell] dumpsys display (displays lógicos):\n")
        val dd = AdbShell.run(ctx, "dumpsys display | grep -iE 'Logical Display|displayId|mBaseDisplayInfo|uniqueId|type=|FLAG_PRIVATE|FLAG_PRESENTATION' | head -50", 9000)
        sb.append(dd.output.ifBlank { "(vazio) ${dd.reason}" }).append("\n")
        sb.append("\n[shell] SurfaceFlinger:\n")
        val sf = AdbShell.run(ctx, "dumpsys SurfaceFlinger --display-id 2>/dev/null; dumpsys SurfaceFlinger 2>/dev/null | grep -iE 'Display .*: ' | head", 9000)
        sb.append(sf.output.ifBlank { "(vazio)" }).append("\n")

        val id = clusterDisplayId(ctx)
        sb.append("\nDisplay do cluster detectado: ").append(if (id >= 0) "#$id" else "nenhum").append("\n")

        sb.append("\n[shell] app em primeiro plano (tentativas):\n")
        val fgRaw = AdbShell.run(ctx, "dumpsys activity activities 2>/dev/null | grep -iE 'ResumedActivity|mFocusedApp|topResumed' | head; echo '--- window ---'; dumpsys window 2>/dev/null | grep -iE 'mCurrentFocus|mFocusedApp' | head", 9000)
        sb.append(fgRaw.output.ifBlank { "(vazio)" }).append("\n")
        val fg = foreground(ctx)
        sb.append("App em primeiro plano (lido): ").append(fg?.second ?: "não identificado").append(fg?.let { " (task ${it.first})" } ?: "").append("\n")
        releaseSurface(ctx)
        return sb.toString()
    }
}
