package com.euler.catalog

import android.content.Context
import android.graphics.Point
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
    // Dicionário oficial BYD (clusterdebug/SecondActivity): sendInfo(1000, code, "").
    //   0 = host restaura stream do cluster · 16 =投屏 TELA CHEIA liga · 17 = 投屏 MEIA-TELA liga
    //   18 = projeção desliga · 29/30/31 = tela 8,8"/12,3"/10,25" · 35 = modo Di4.0 (redesenha gauges!)
    // ON = 0 → 16 (restaura o stream e liga tela cheia). Caminho conhecido bom (v36–v39).
    private val ON_SEQUENCE = listOf(0, 16)
    // Meia-tela (17): app numa parte, gauges na outra, sem sobrepor.
    private val ON_SEQUENCE_HALF = listOf(0, 17)
    // Sequência de DESLIGAR (volta o painel original da BYD): 18 → (1,5s) → 0.
    private val OFF_SEQUENCE = listOf(18, 0)
    // Modo de projeção atual (false = tela cheia / true = meia-tela). Afeta o próximo openSurface.
    @Volatile var halfScreen: Boolean = false

    data class Result(val ok: Boolean, val detail: String)

    /** Último app jogado no cluster, pra conseguir trazer de volta. */
    @Volatile var lastPkg: String = ""
    @Volatile var lastComp: String = ""
    @Volatile var lastDisplayId: Int = -1

    // ---------- perfil do cluster (detecção automática por tamanho) ----------

    data class ClusterProfile(val found: Boolean, val displayId: Int, val w: Int, val h: Int, val density: Int) {
        val key get() = "${w}x${h}"
        /** Polegadas são do catálogo do modelo, não medidas (o display virtual reporta dpi sintético). */
        val label get() = when {
            !found -> "não detectado"
            w == 1280 && h == 480 -> "widescreen ~8,8\" ($key)"
            else -> "$key (perfil desconhecido)"
        }
    }

    /** Geometria a aplicar num app: densidade + margens (px) reservadas nos 4 lados (overscan L,T,R,B). */
    data class AppGeom(val density: Int, val insetLeft: Int, val insetTop: Int, val insetRight: Int, val insetBottom: Int)

    /** Densidade-base: mira ~480dp no lado curto (paisagem tipo tablet). 480px de altura → 160dpi. */
    private fun baseDensity(h: Int): Int = (h * 160 / 480).coerceIn(140, 220)

    // Margens medidas manualmente no cluster 1280×480 (redimensionador). Escaladas p/ outros tamanhos.
    private fun scaleX(v: Int, w: Int) = (v * w / 1280)
    private fun scaleY(v: Int, h: Int) = (v * h / 480)

    private fun geomFor(pkg: String, w: Int, h: Int): AppGeom {
        val base = baseDensity(h)
        val p = pkg.lowercase()
        return when {
            // Waze/mapas: L105 T0 R118 B18 — base bem baixa, "tempo de chegada" alinha com a linha de bateria/km do BYD.
            p.contains("waze") || p.contains("maps") || p.contains("navi") ->
                AppGeom((base - 10).coerceAtLeast(140), scaleX(105, w), scaleY(0, h), scaleX(118, w), scaleY(18, h))
            // Vídeo: L120 T54 R120 B73 (área ~1040×353).
            p.contains("youtube") || p.contains("video") || p.contains("player") || p.contains("netflix") ||
            p.contains("tv") || p.contains("iptv") || p.contains("mxtech") || p.contains("vlc") ->
                AppGeom(base, scaleX(120, w), scaleY(54, h), scaleX(120, w), scaleY(73, h))
            else -> AppGeom(base, 0, 0, 0, 0)   // resto: tela cheia
        }
    }

    /** Objeto Display do cluster (pra ler a resolução real), ou null. */
    private fun clusterDisplayObj(ctx: Context): Display? {
        return try {
            val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val pres = dm.getDisplays("android.hardware.display.category.PRESENTATION")
            pres.firstOrNull { it.displayId > 0 && isClusterName(it.name) }
                ?: pres.firstOrNull { it.displayId > 0 }
                ?: dm.displays.firstOrNull { it.displayId > 0 && isClusterName(it.name) }
        } catch (e: Exception) { null }
    }

    /** Lê o perfil do cluster já criado (resolução + densidade). Chamar depois do openSurface. */
    private fun readProfile(ctx: Context): ClusterProfile {
        val d = clusterDisplayObj(ctx) ?: return ClusterProfile(false, -1, 0, 0, 0)
        val p = Point(); try { d.getRealSize(p) } catch (e: Exception) {}
        val dens = try {
            val dmetrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") d.getRealMetrics(dmetrics)
            dmetrics.densityDpi
        } catch (e: Exception) { 0 }
        return ClusterProfile(true, d.displayId, p.x, p.y, dens)
    }

    /** Aplica a geometria no display do cluster: densidade + margens (topo/base) pras barras do BYD. */
    private fun applyGeom(ctx: Context, id: Int, w: Int, h: Int, g: AppGeom) {
        AdbShell.run(ctx, "wm density ${g.density} -d $id 2>&1", 8000)
        if (g.insetLeft > 0 || g.insetTop > 0 || g.insetRight > 0 || g.insetBottom > 0) {
            // overscan = left,top,right,bottom (px).
            AdbShell.run(ctx, "wm overscan ${g.insetLeft},${g.insetTop},${g.insetRight},${g.insetBottom} -d $id 2>&1", 8000)
        } else {
            AdbShell.run(ctx, "wm overscan reset -d $id 2>&1", 8000)
        }
    }

    private fun resetGeom(ctx: Context, id: Int) {
        if (id < 0) return
        AdbShell.run(ctx, "wm density reset -d $id 2>&1; wm overscan reset -d $id 2>&1", 8000)
    }

    // ---------- superfície do cluster ----------

    /** Um comando service call com s16 null. true se a central respondeu Result: Parcel(00000000. */
    private fun callCode(ctx: Context, code: Int): Boolean {
        val r = AdbShell.run(ctx, "service call $SVC 2 i32 1000 i32 $code s16 null 2>&1", 8000)
        return r.ok && r.output.contains("Parcel(00000000", true)
    }

    /**
     * Decodifica a resposta crua do `service call` (hexdump do Parcel).
     * Palavra0 = código de exceção (0 = ok); se !=0, palavra1 = tamanho da msg e o resto é UTF-16LE.
     */
    private fun decodeReply(raw: String): String {
        // Coleta as palavras de dados (ignora a coluna de endereço "0x...:").
        val words = ArrayList<Long>()
        for (line in raw.lines()) {
            val colon = line.indexOf(':')
            val body = if (line.trimStart().startsWith("0x") && colon >= 0) line.substring(colon + 1) else continue
            val hex = body.substringBefore('\'') // corta a parte ASCII à direita
            for (tok in Regex("""[0-9a-fA-F]{8}""").findAll(hex)) words.add(tok.value.toLong(16))
        }
        if (words.isEmpty()) return raw.replace("\n", " ").take(100)
        val w0 = words[0].toInt()
        if (w0 == 0) return "ok (Parcel 0)"
        val exc = when (w0) {
            -1 -> "Security"; -2 -> "BadParcelable"; -3 -> "IllegalArgument"; -4 -> "NullPointer"
            -5 -> "IllegalState"; -6 -> "NetworkOnMainThread"; -7 -> "UnsupportedOperation"; -8 -> "ServiceSpecific"
            else -> "exc($w0)"
        }
        // Mensagem: palavra1 = nº de chars UTF-16; chars a partir da palavra2 (2 por palavra, little-endian).
        val msg = StringBuilder()
        if (words.size > 1) {
            val len = words[1].toInt().coerceIn(0, 200)
            var idx = 2
            var count = 0
            while (count < len && idx < words.size) {
                val wd = words[idx]
                val c0 = (wd and 0xFFFF).toInt()
                val c1 = ((wd shr 16) and 0xFFFF).toInt()
                if (count < len && c0 in 1..0xFFFF) { msg.append(c0.toChar()); count++ }
                if (count < len && c1 in 1..0xFFFF) { msg.append(c1.toChar()); count++ }
                idx++
            }
        }
        return "EXCEÇÃO $exc" + if (msg.isNotBlank()) ": \"${msg.toString().trim()}\"" else ""
    }

    /** Sonda um opcode isolado e devolve a resposta decodificada (pra firmwares diferentes). */
    private fun probeCode(ctx: Context, code: Int): String {
        val r = AdbShell.run(ctx, "service call $SVC 2 i32 1000 i32 $code s16 null 2>&1", 6000)
        if (!r.ok && r.output.isBlank()) return "falhou no shell: ${r.reason}"
        if (r.output.contains("does not exist", true)) return "serviço não existe"
        return decodeReply(r.output)
    }

    /**
     * Liga a projeção do painel: 0 (restaura stream) → 16 tela cheia (ou 17 meia-tela).
     * O com.xdja.containerservice cria o display fission_bg_xdjaVirtualSurface.
     * Retorna true se todos os comandos responderam ok.
     */
    private fun openSurface(ctx: Context): Boolean {
        var allOk = true
        val seq = if (halfScreen) ON_SEQUENCE_HALF else ON_SEQUENCE
        for ((i, code) in seq.withIndex()) {
            if (i > 0) Thread.sleep(1000)
            if (!callCode(ctx, code)) allOk = false
        }
        return allOk
    }

    /** Desliga a projeção e volta o painel original da BYD (18 → 1,5s → 0). */
    private fun releaseSurface(ctx: Context) {
        callCode(ctx, OFF_SEQUENCE[0])   // 18 — projeção off
        Thread.sleep(1500)
        callCode(ctx, OFF_SEQUENCE[1])   // 0 — restaura painel original
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

    /** Joga o app que está na tela pro cluster: cria a superfície, detecta o perfil, ajusta e relança. */
    /** Espera a segunda tela do cluster aparecer, até timeoutMs. Retorna o id (>0) ou -1. */
    private fun waitForDisplay(ctx: Context, timeoutMs: Long): Int {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            val id = clusterDisplayId(ctx); if (id > 0) return id; Thread.sleep(200)
        }
        return -1
    }

    fun throwToCluster(ctx: Context): Result {
        val seqStr = ON_SEQUENCE.joinToString("→")
        var seqOk = openSurface(ctx)
        var id = waitForDisplay(ctx, 7000)
        // Self-heal: se não criou a tela, o serviço do cluster pode ter travado. Reinicia e tenta de novo.
        if (id < 0) {
            AdbShell.resetClusterService(ctx)
            Thread.sleep(2500)
            seqOk = openSurface(ctx)
            id = waitForDisplay(ctx, 9000)
        }
        if (id < 0) { releaseSurface(ctx); return Result(false, "não achei o display do cluster mesmo após reiniciar o serviço (sequência $seqStr ${if (seqOk) "respondeu ok" else "falhou em algum passo"}). Tente reiniciar a multimídia.") }

        // Perfil do cluster (resolução + densidade reais) — decide a geometria por tamanho.
        val prof = readProfile(ctx)
        val pkg = foregroundPackage(ctx) ?: run { releaseSurface(ctx); return Result(false, "nenhum app em foco na tela") }
        val comp = resolveLaunchComponent(ctx, pkg) ?: run { releaseSurface(ctx); return Result(false, "sem activity pública em $pkg") }

        // Ajusta densidade/letterbox conforme o perfil, ANTES de abrir o app.
        val geom = geomFor(pkg, if (prof.found && prof.w > 0) prof.w else 1280, if (prof.found && prof.h > 0) prof.h else 480)
        applyGeom(ctx, id, prof.w, prof.h, geom)

        // Move a stack e relança o launcher no display do cluster (o que de fato renderiza).
        moveStackToDisplay(ctx, pkg, id)
        val r = AdbShell.run(ctx, "am start --display $id -n $comp 2>&1", 10000)
        if (r.output.contains("Error", true) || r.output.contains("Exception", true)) {
            resetGeom(ctx, id); releaseSurface(ctx)
            return Result(false, "falhei ao abrir no cluster: ${r.output.lineSequence().firstOrNull { it.isNotBlank() }?.take(120) ?: r.reason}")
        }
        lastPkg = pkg; lastComp = comp; lastDisplayId = id
        val extra = if (prof.found) " · ${prof.label} · dens ${geom.density}${if (geom.insetLeft > 0 || geom.insetTop > 0 || geom.insetRight > 0 || geom.insetBottom > 0) " · margem ${geom.insetLeft}/${geom.insetTop}/${geom.insetRight}/${geom.insetBottom}" else ""}" else ""
        return Result(true, "$pkg → cluster$extra")
    }

    /** Traz de volta pra tela principal o que foi jogado no cluster, reseta a geometria e libera. */
    fun bringBack(ctx: Context): Result {
        var moved = false
        resetGeom(ctx, lastDisplayId)
        if (lastPkg.isNotEmpty()) {
            moveStackToDisplay(ctx, lastPkg, 0)
            val comp = lastComp.ifEmpty { resolveLaunchComponent(ctx, lastPkg) ?: lastPkg }
            val r = AdbShell.run(ctx, "am start --display 0 -n $comp 2>&1", 10000)
            moved = !(r.output.contains("Error", true) || r.output.contains("Exception", true))
        }
        releaseSurface(ctx)
        lastPkg = ""; lastComp = ""; lastDisplayId = -1
        return Result(moved, if (moved) "voltou pra tela do carro" else "liberei o cluster")
    }

    /** true se há algo jogado no cluster agora (controle do ícone: jogar x voltar). */
    fun isCasting(): Boolean = lastPkg.isNotEmpty()

    // ---------- diagnóstico (primeiro teste no carro) ----------

    /** Relatório pra confirmar, no carro, o código de abertura e o display certos. */
    fun diagnose(ctx: Context): String {
        val sb = StringBuilder()

        // Firmware do carro (ajuda a casar qual variante de cluster usar).
        sb.append("Firmware / modelo:\n")
        val fw = AdbShell.run(ctx, "getprop ro.build.display.id; getprop ro.build.version.incremental; getprop ro.product.model; getprop | grep -iE 'dilink|byd.*version' | head -6", 8000)
        sb.append(fw.output.lines().filter { it.isNotBlank() }.joinToString("\n") { "  $it" }.ifBlank { "  (vazio)" }).append("\n\n")

        // Pacotes de cluster do fornecedor (se não existir, esse firmware não projeta no painel).
        sb.append("Pacotes de cluster (xdja/container):\n")
        val pk = AdbShell.run(ctx, "pm list packages 2>/dev/null | grep -iE 'xdja|container|cluster|instrument' | head -10", 8000)
        sb.append(pk.output.ifBlank { "  (nenhum encontrado)\n" }).append("\n")

        // Sondagem de opcodes: testa cada um e decodifica a resposta (ok x exceção + mensagem).
        sb.append("Sondagem de opcodes do AutoContainer (decodificada):\n")
        for (code in listOf(0, 16, 17, 18, 29, 35, 45, 46)) {
            sb.append("  code $code → ").append(probeCode(ctx, code)).append("\n")
            Thread.sleep(300)
        }
        sb.append("\n")

        sb.append("Displays antes de abrir:\n").append(displaysText(ctx)).append("\n\n")
        sb.append("Ligando a projeção (sequência ${ON_SEQUENCE.joinToString(" → ")}, s16 null, ~1s entre cada):\n")
        for ((i, code) in ON_SEQUENCE.withIndex()) {
            if (i > 0) Thread.sleep(1000)
            val r = AdbShell.run(ctx, "service call $SVC 2 i32 1000 i32 $code s16 null 2>&1", 6000)
            val ok = r.ok && r.output.contains("Parcel(00000000", true)
            sb.append("  $SVC code $code → ${if (ok) "ok" else decodeReply(r.output)}\n")
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

        // Perfil do cluster + geometria que seria aplicada ao app em foco.
        val prof = readProfile(ctx)
        sb.append("Perfil do cluster: ").append(prof.label)
        if (prof.found) sb.append(" · densidade ${prof.density}dpi")
        sb.append("\n")
        val pkgNow = foregroundPackage(ctx)
        if (prof.found && pkgNow != null) {
            val g = geomFor(pkgNow, prof.w, prof.h)
            sb.append("Geometria p/ $pkgNow: densidade ${g.density}${if (g.insetLeft > 0 || g.insetTop > 0 || g.insetRight > 0 || g.insetBottom > 0) " · margem L${g.insetLeft}/T${g.insetTop}/R${g.insetRight}/B${g.insetBottom}" else ""}\n")
        }

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
