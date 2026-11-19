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

    // O serviço real do fornecedor é "AutoContainer" (o "auto_container" não existe no v33).
    private const val SVC = "AutoContainer"
    // Sequência de LIGAR a projeção no v33 (DiLink5), confirmada no app de referência:
    //   16 (prepara) → 35 (cria a superfície fission_bg_xdjaVirtualSurface) → 46 (mostra no painel).
    // Cada comando com ~1s de intervalo, todos com s16 null.
    private val ON_SEQUENCE = listOf(16, 35, 46)
    // Sequência de DESLIGAR (volta o painel original da BYD): 46 → (1,5s) → 18 → 0.
    private val OFF_SEQUENCE = listOf(46, 18, 0)

    data class Result(val ok: Boolean, val detail: String)

    /** Último app jogado no cluster, pra conseguir trazer de volta. */
    @Volatile var lastPkg: String = ""
    @Volatile var lastComp: String = ""

    // ---------- superfície do cluster ----------

    /** Um comando service call com s16 null. true se a central respondeu Result: Parcel(00000000. */
    private fun callCode(ctx: Context, code: Int): Boolean {
        val r = AdbShell.run(ctx, "service call $SVC 2 i32 1000 i32 $code s16 null 2>&1", 8000)
        return r.ok && r.output.contains("Parcel(00000000", true)
    }

    /**
     * Liga a projeção do painel: manda a sequência 16 → 35 → 46 (s16 null), ~1s entre cada.
     * É o 35 que faz o com.xdja.containerservice criar o display fission_bg_xdjaVirtualSurface.
     * Retorna true se todos os comandos responderam ok.
     */
    private fun openSurface(ctx: Context): Boolean {
        var allOk = true
        for ((i, code) in ON_SEQUENCE.withIndex()) {
            if (i > 0) Thread.sleep(1000)
            if (!callCode(ctx, code)) allOk = false
        }
        return allOk
    }

    /** Desliga a projeção e volta o painel original da BYD (46 → 1,5s → 18 → 0). */
    private fun releaseSurface(ctx: Context) {
        callCode(ctx, OFF_SEQUENCE[0])   // 46
        Thread.sleep(1500)
        callCode(ctx, OFF_SEQUENCE[1])   // 18
        Thread.sleep(1000)
        callCode(ctx, OFF_SEQUENCE[2])   // 0
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

    // ---------- app/tarefa (fluxo BYD confirmado no ClusterCast webdelta) ----------

    /** Pacote do app em primeiro plano na tela do carro (display 0). Ignora o próprio Unlock BYD. */
    private fun foregroundPackage(ctx: Context): String? {
        val out = AdbShell.run(ctx, "dumpsys activity activities 2>/dev/null", 8000).output
        if (out.isBlank()) return null
        // Recorta a seção do "Display #0" (onde fica o app da multimídia).
        val i0 = out.indexOf("Display #0")
        val region = if (i0 >= 0) {
            val next = out.indexOf("Display #", i0 + 9)
            if (next > i0) out.substring(i0, next) else out.substring(i0)
        } else out
        // Primeiro mResumedActivity que não seja o nosso app.
        val rx = Regex("""mResumedActivity:.*?\{[^ ]+ u\d+ ([^/]+)/""")
        for (m in rx.findAll(region)) {
            val pkg = m.groupValues[1]
            if (pkg != ctx.packageName) return pkg
        }
        return null
    }

    /** Resolve a activity pública/launcher do pacote: cmd package resolve-activity --brief. */
    private fun resolveLaunchComponent(ctx: Context, pkg: String): String? {
        val out = AdbShell.run(ctx, "cmd package resolve-activity --brief $pkg 2>/dev/null", 8000).output
        for (line in out.lines()) {
            val s = line.trim()
            if (s.contains("/") && !s.contains(" ") && s.startsWith(pkg)) return s
        }
        return null
    }

    /** Move a stack do pacote para o display alvo (am display move-stack). Best-effort. */
    private fun moveStackToDisplay(ctx: Context, pkg: String, display: Int): Boolean {
        val out = AdbShell.run(ctx, "am stack list 2>/dev/null", 8000).output
        if (out.isBlank()) return false
        val header = Regex("""Stack id=(\d+).*?displayId=(\d+)""")
        var stackId = -1
        for (line in out.lines()) {
            val m = header.find(line)
            if (m != null) stackId = m.groupValues[1].toIntOrNull() ?: stackId
            if (line.contains("$pkg/")) { if (stackId >= 0) break }
        }
        if (stackId < 0) return false
        AdbShell.run(ctx, "am display move-stack $stackId $display 2>&1", 8000)
        return true
    }

    // ---------- ações ----------

    /** Joga o app que está na tela pro cluster: cria a superfície, resolve o launcher e relança no display 1. */
    fun throwToCluster(ctx: Context): Result {
        val seqOk = openSurface(ctx)
        // O display só aparece depois do 35: o app de referência procura por até ~6s.
        var id = -1
        val until = System.currentTimeMillis() + 7000
        while (System.currentTimeMillis() < until) { id = clusterDisplayId(ctx); if (id > 0) break; Thread.sleep(200) }
        if (id < 0) { releaseSurface(ctx); return Result(false, "não achei o display do cluster (sequência 16→35→46 ${if (seqOk) "respondeu ok" else "falhou em algum passo"})") }

        val pkg = foregroundPackage(ctx) ?: run { releaseSurface(ctx); return Result(false, "nenhum app em foco na tela") }
        val comp = resolveLaunchComponent(ctx, pkg) ?: run { releaseSurface(ctx); return Result(false, "sem activity pública em $pkg") }

        // Move a stack e relança o launcher no display do cluster (o que de fato renderiza).
        moveStackToDisplay(ctx, pkg, id)
        val r = AdbShell.run(ctx, "am start --display $id -n $comp 2>&1", 10000)
        if (r.output.contains("Error", true) || r.output.contains("Exception", true)) {
            releaseSurface(ctx)
            return Result(false, "falhei ao abrir no cluster: ${r.output.lineSequence().firstOrNull { it.isNotBlank() }?.take(120) ?: r.reason}")
        }
        lastPkg = pkg; lastComp = comp
        return Result(true, "$pkg → cluster #$id")
    }

    /** Traz de volta pra tela principal o que foi jogado no cluster, e libera a superfície. */
    fun bringBack(ctx: Context): Result {
        var moved = false
        if (lastPkg.isNotEmpty()) {
            moveStackToDisplay(ctx, lastPkg, 0)
            val comp = lastComp.ifEmpty { resolveLaunchComponent(ctx, lastPkg) ?: lastPkg }
            val r = AdbShell.run(ctx, "am start --display 0 -n $comp 2>&1", 10000)
            moved = !(r.output.contains("Error", true) || r.output.contains("Exception", true))
        }
        releaseSurface(ctx)
        lastPkg = ""; lastComp = ""
        return Result(moved, if (moved) "voltou pra tela do carro" else "liberei o cluster")
    }

    /** true se há algo jogado no cluster agora (controle do ícone: jogar x voltar). */
    fun isCasting(): Boolean = lastPkg.isNotEmpty()

    // ---------- diagnóstico (primeiro teste no carro) ----------

    /** Relatório pra confirmar, no carro, o código de abertura e o display certos. */
    fun diagnose(ctx: Context): String {
        val sb = StringBuilder()
        sb.append("Displays antes de abrir:\n").append(displaysText(ctx)).append("\n\n")
        sb.append("Ligando a projeção (sequência 16 → 35 → 46, s16 null, ~1s entre cada):\n")
        for ((i, code) in ON_SEQUENCE.withIndex()) {
            if (i > 0) Thread.sleep(1000)
            val r = AdbShell.run(ctx, "service call $SVC 2 i32 1000 i32 $code s16 null 2>&1", 6000)
            val resumo = r.output.replace("\n", " ").take(80).ifBlank { r.reason }
            sb.append("  $SVC code $code → ${if (r.ok && r.output.contains("Parcel(00000000", true)) "ok" else "?"}: $resumo\n")
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
        val pkg = foregroundPackage(ctx)
        sb.append("Pacote em foco (display 0): ").append(pkg ?: "não identificado").append("\n")
        if (pkg != null) sb.append("Launcher resolvido: ").append(resolveLaunchComponent(ctx, pkg) ?: "(sem activity pública)").append("\n")
        releaseSurface(ctx)
        return sb.toString()
    }
}
