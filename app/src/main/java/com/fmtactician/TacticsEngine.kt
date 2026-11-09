package com.fmtactician

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

data class Ln(val t: String, val x: Int, val y: Int, val w: Int, val h: Int) {
    val cx get() = x + w / 2
    val cy get() = y + h / 2
}
class Scan(val lines: List<Ln>, val blocks: List<Ln>, val text: String, val w: Int, val h: Int, val bmp: Bitmap)

class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("fmdata2", Context.MODE_PRIVATE)
    var d = JSONObject(sp.getString("d", "{}") ?: "{}")
    fun reload() { d = JSONObject(sp.getString("d", "{}") ?: "{}") }
    fun save() { sp.edit().putString("d", d.toString()).apply() }
    fun obj(k: String): JSONObject = d.optJSONObject(k) ?: JSONObject().also { d.put(k, it) }
    fun str(k: String): String = d.optString(k, "")
    fun put(k: String, v: String) { d.put(k, v); save() }
    fun clear() { d = JSONObject(); save() }
    fun clearOpp() { d.remove("opp"); save() }
}
fun JSONArray?.strs(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
fun JSONObject.strs(k: String): List<String> = optJSONArray(k).strs()
fun JSONObject.addStrs(k: String, items: List<String>) {
    val cur = strs(k).toMutableList()
    items.forEach { if (it.isNotBlank() && it !in cur) cur.add(it) }
    put(k, JSONArray(cur))
}

/** Reads FM26 Mobile screens (landscape) from OCR results. */
object Parse {
    const val R = 0.55
    val CODE = Regex("^(GK|DR|DL|DC|WBR|WBL|DMR|DML|DMC|MR|ML|MC|AMR|AML|AMC|FC|ST)$")
    val SQ = Regex("^\\p{L}[\\p{L}'’ .-]*, \\p{Lu}\\.?$")
    val FORM = Regex("\\b[2-5]-[1-5](-[1-5]){1,3}\\b")
    private val CAPS = Regex("^[A-Z][A-Z .'’-]{3,30}$")
    private val LABELS = setOf("STYLE", "FORMATION", "KEY PLAYER", "WEAK POINT", "STRENGTHS", "WEAKNESSES", "MATCH TACTICS")
    private val ATTR_BAD = setOf("condition", "morale", "form", "value", "wage", "age")

    val OPTS = linkedMapOf(
        "Team Mentality" to listOf("Contain", "Defensive", "Counter", "Balanced", "Control", "Attacking", "Overload"),
        "Width" to listOf("Narrow", "Balanced", "Wide"),
        "Passing Tempo" to listOf("Slow", "Normal", "Fast"),
        "Creative Freedom" to listOf("Disciplined", "Balanced", "Expressive"),
        "Defensive Line" to listOf("Deep", "Balanced", "High"),
        "Closing Down" to listOf("Sit Back", "Own Half", "All Over"),
        "Tackling" to listOf("Cautious", "Normal", "Committed"),
        "Offside Trap" to listOf("No", "Yes"),
        "Time Wasting" to listOf("No", "Yes"),
        "Final Third" to listOf("Early Crosses", "Look For Overlap", "Shoot On Sight", "Work Into Box", "Run At Defence", "Through Balls"),
        "Passing Style" to listOf("Short", "Mixed", "Direct", "Long"),
        "Passing Focus" to listOf("Left", "Centre", "Right", "Both Flanks", "Mixed"),
        "Goalkeeper Distribution" to listOf("Short", "Mixed", "Long")
    )
    val PAGE = mapOf(
        "shape" to listOf("Team Mentality", "Width", "Passing Tempo", "Creative Freedom"),
        "defence" to listOf("Defensive Line", "Closing Down", "Tackling", "Offside Trap", "Time Wasting"),
        "attack" to listOf("Final Third", "Passing Style", "Passing Focus", "Goalkeeper Distribution")
    )

    fun screen(t: String): String {
        val l = t.lowercase()
        return when {
            "player profile" in l -> "player"
            "pre-match hub" in l -> "hub"
            "next match" in l -> "report"
            "team mentality" in l || "creative freedom" in l -> "shape"
            "defensive line" in l || "closing down" in l || "offside trap" in l -> "defence"
            "final third" in l || "passing style" in l || "goalkeeper distribution" in l -> "attack"
            "takers" in l || "penalty kick" in l || "corner" in l || "free kick" in l -> "setpieces"
            "tactics - formation" in l || ("squad" in l && ("create tactic" in l || "team report" in l)) -> "formation"
            else -> "unknown"
        }
    }

    private fun inHalf(s: Scan, l: Ln, half: Int) =
        half == 0 || (half == 1 && l.cx < s.w * R) || (half == 2 && l.cx >= s.w * R)

    /** Value printed directly under a label (e.g. STYLE -> Attacking). */
    fun valueBelow(s: Scan, label: String, half: Int): String {
        val lb = s.lines.firstOrNull { it.t.equals(label, true) && inHalf(s, it, half) } ?: return ""
        return s.lines.filter {
            it !== lb && it.y > lb.y + lb.h * 0.5 && it.y < lb.y + lb.h * 3.5 && inHalf(s, it, half) &&
                Math.abs(it.cx - lb.cx) < maxOf(lb.w, it.w)
        }.minByOrNull { it.y }?.t ?: ""
    }

    fun teamName(s: Scan): String =
        s.lines.filter { it.y < s.h * 0.09 && it.cx > s.w * 0.1 && it.cx < s.w * 0.5 && it.t.length in 3..30 && !it.t[0].isDigit() }
            .minByOrNull { it.y }?.t ?: ""

    private fun shapeOf(codes: List<String>): String {
        val out = codes.filter { it != "GK" }
        if (out.size != 10) return ""
        fun c(vararg k: String) = out.count { it in k }
        return listOf(c("DR", "DL", "DC", "WBR", "WBL"), c("DMR", "DML", "DMC"), c("MR", "ML", "MC"),
            c("AMR", "AML", "AMC"), c("FC", "ST")).filter { it > 0 }.joinToString("-")
    }

    fun lineup(s: Scan, st: Store): String {
        val codes = s.lines.filter { CODE.matches(it.t) }
        val found = LinkedHashMap<String, String>()
        s.lines.filter { SQ.matches(it.t) }.sortedBy { it.y }.take(11).forEach { n ->
            codes.filter { it.x < n.x && Math.abs(it.cy - n.cy) < n.h * 1.2 }.minByOrNull { n.x - it.x }?.let { found[n.t] = it.t }
        }
        if (found.isEmpty()) return "No squad list found. Open Tactics > Formation and make sure the Squad list is visible."
        var lu = st.obj("lineup")
        found.forEach { (k, v) -> lu.put(k, v) }
        if (lu.length() > 11) { lu = JSONObject(); found.forEach { (k, v) -> lu.put(k, v) }; st.d.put("lineup", lu) }
        val sh = shapeOf(lu.keys().asSequence().map { lu.getString(it) }.toList())
        if (sh.isNotBlank()) st.d.put("derivedForm", sh)
        return "LINEUP saved (${lu.length()} of 11 starters)\n" + found.entries.joinToString("\n") { "${it.value} ${it.key}" } +
            (if (lu.length() < 11) "\nTip: scroll the squad list a little and scan again to capture the last starters." else "")
    }

    private fun purple(b: Bitmap, x: Int, y: Int): Boolean {
        if (x < 0 || y < 0 || x >= b.width || y >= b.height) return false
        val c = b.getPixel(x, y)
        val g = Color.green(c); val bl = Color.blue(c)
        return bl > 140 && g < 90 && bl - g > 90 && Color.red(c) < bl
    }

    /** Reads which options are highlighted (purple) on Shape / Defence / Attack pages. */
    fun tactic(s: Scan, page: String, st: Store): String {
        val groups = PAGE[page] ?: return ""
        val heads = groups.mapNotNull { g -> s.lines.firstOrNull { it.t.equals(g, true) && it.cx > s.w * R }?.let { g to it.y } }
        if (heads.isEmpty()) return "Couldn't read the settings on this screen."
        val picked = LinkedHashMap<String, MutableList<String>>()
        for (l in s.lines) {
            if (l.cx < s.w * R) continue
            val g = heads.filter { it.second <= l.y }.maxByOrNull { it.second }?.first ?: continue
            val opt = OPTS[g]?.firstOrNull { it.equals(l.t, true) } ?: continue
            val m = maxOf(6, l.h / 3)
            if (purple(s.bmp, l.x - m, l.cy) || purple(s.bmp, l.x + l.w + m, l.cy)) picked.getOrPut(g) { mutableListOf() }.add(opt)
        }
        val tac = st.obj("tactic")
        picked.forEach { (g, v) -> tac.put(g, JSONArray(v)) }
        if (picked.isEmpty()) return "Couldn't tell which options are selected. Try again with the page fully visible."
        return "TACTICS (${page.uppercase()}) saved\n" + picked.entries.joinToString("\n") { "${it.key}: ${it.value.joinToString()}" }
    }

    fun player(s: Scan, st: Store): String {
        val nameRe = Regex("^\\d+\\.\\s*(.+)$")
        val name = s.lines.firstOrNull { it.y < s.h * 0.14 && nameRe.matches(it.t) }?.let { nameRe.find(it.t)!!.groupValues[1].trim() }
            ?: return "Couldn't read the player's name. Open the Player Profile screen and try again."
        val attrs = JSONObject()
        val nums = s.lines.filter { Regex("^\\d{1,2}$").matches(it.t) && it.t.toInt() in 1..20 }
        val labels = s.lines.filter { Regex("^[A-Za-z][A-Za-z .]{2,17}$").matches(it.t) && it.t.lowercase() !in ATTR_BAD }
        for (n in nums) {
            val lb = labels.filter { it.x + it.w <= n.x + 4 && Math.abs(it.cy - n.cy) < n.h * 0.8 }.maxByOrNull { it.x } ?: continue
            attrs.put(lb.t, n.t.toInt())
        }
        val merged = Regex("^([A-Za-z][A-Za-z .]{2,17}?)\\s+(\\d{1,2})$")
        s.lines.forEach { l ->
            merged.find(l.t)?.let { m ->
                val v = m.groupValues[2].toInt()
                if (v in 1..20 && m.groupValues[1].trim().lowercase() !in ATTR_BAD) attrs.put(m.groupValues[1].trim(), v)
            }
        }
        if (attrs.length() < 5) return "Couldn't read this player's attributes. Open the Player Profile and try again."
        s.lines.firstOrNull { it.t.contains("/") && it.t.contains("(") }?.let { attrs.put("_pos", it.t) }
        st.obj("players").put(name, attrs)
        val list = attrs.keys().asSequence().filter { !it.startsWith("_") }.map { it to attrs.getInt(it) }.toList()
        val str = list.filter { it.second >= 13 }.sortedByDescending { it.second }.joinToString { "${it.first} ${it.second}" }
        val wk = list.filter { it.second <= 9 }.sortedBy { it.second }.joinToString { "${it.first} ${it.second}" }
        return "PLAYER saved: $name\nStrengths: ${str.ifBlank { "-" }}\nWeaknesses: ${wk.ifBlank { "-" }}"
    }

    private fun oppName(s: Scan): String {
        val n = s.lines.filter { it.cx >= s.w * R && it.y < s.h * 0.3 && CAPS.matches(it.t) && it.t !in LABELS }
            .minByOrNull { it.y }?.t ?: return ""
        return n.lowercase().split(" ").joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } }
    }

    fun opp(s: Scan, st: Store, hub: Boolean): String {
        var o = st.obj("opp")
        val name = if (hub) oppName(s) else ""
        val style = valueBelow(s, "STYLE", 2)
        val shape = FORM.find(valueBelow(s, "FORMATION", 2))?.value ?: ""
        val key = valueBelow(s, "KEY PLAYER", 2); val weak = valueBelow(s, "WEAK POINT", 2)
        val newMatch = (name.isNotBlank() && o.optString("name").isNotBlank() && !name.equals(o.optString("name"), true)) ||
            (key.isNotBlank() && o.optString("key").isNotBlank() && key != o.optString("key"))
        if (newMatch) { st.d.remove("opp"); o = st.obj("opp") }
        listOf("name" to name, "style" to style, "shape" to shape, "key" to key, "weak" to weak).forEach { (k, v) -> if (v.isNotBlank()) o.put(k, v) }
        if (hub) {
            FORM.find(valueBelow(s, "Formation", 1))?.value?.let { st.d.put("hubForm", it) }
            valueBelow(s, "Style", 1).takeIf { it.isNotBlank() }?.let { st.d.put("myStyle", it) }
        }
        val heads = s.lines.filter { it.cx >= s.w * R && (it.t.equals("strengths", true) || it.t.equals("weaknesses", true)) }
        val str = mutableListOf<String>(); val wk = mutableListOf<String>()
        for (b in s.blocks) {
            if (b.cx < s.w * R) continue
            val above = heads.filter { it.y < b.y - 2 }
            if (above.isEmpty()) continue
            val hd = (if (hub) above.minByOrNull { Math.abs(it.cx - b.cx) } else above.maxByOrNull { it.y }) ?: continue
            if (hub && b.y > hd.y + s.h * 0.25) continue
            val t = b.t.replace(Regex("^[^\\p{L}0-9]+"), "").trim()
            if (t.length < 3 || t.uppercase() in LABELS) continue
            if (hd.t.equals("strengths", true)) str.add(t) else wk.add(t)
        }
        o.addStrs("str", str); o.addStrs("wk", wk)
        if (!hub) {
            val codes = s.lines.filter { CODE.matches(it.t) && it.cx < s.w * R }
            val lu = o.optJSONObject("lineup") ?: JSONObject().also { o.put("lineup", it) }
            s.lines.filter { SQ.matches(it.t) && it.cx < s.w * R }.forEach { n ->
                codes.filter { it.y < n.y && n.y - it.y < n.h * 5 && Math.abs(it.cx - n.cx) < maxOf(it.w, n.w) }
                    .maxByOrNull { it.y }?.let { lu.put(n.t, it.t) }
            }
        }
        val tip = if (o.strs("str").isEmpty() || o.strs("wk").isEmpty())
            "\nTip: on the Report, scroll the right panel so both Strengths and Weaknesses are shown, scanning each time." else ""
        return "OPPONENT saved: ${o.optString("name").ifBlank { "?" }} | ${o.optString("style")} | ${o.optString("shape")}\n" +
            "Strengths found: ${o.strs("str").size}, Weaknesses found: ${o.strs("wk").size}$tip"
    }
}

/** Turns saved data into recommendations using FM26's own option names. */
object Advisor {
    private fun has(s: String, vararg k: String) = k.any { it in s }
    private val GROUPS = mapOf(
        "pace" to listOf("Pace"), "aerial" to listOf("Aerial", "Strength"), "passing" to listOf("Passing", "Technique"),
        "workrate" to listOf("Stamina", "Aggression", "Teamwork"), "dribbling" to listOf("Dribbling"),
        "crossing" to listOf("Crossing"), "shooting" to listOf("Shooting"),
        "defending" to listOf("Tackling", "Positioning"), "mental" to listOf("Decisions", "Teamwork")
    )

    private fun sur(n: String) = (if (',' in n) n.substringBefore(',') else n.substringAfterLast(' ')).trim().lowercase()

    fun traits(st: Store): Pair<Set<String>, Set<String>> {
        val ps = st.obj("players"); val lu = st.obj("lineup")
        val starters = lu.keys().asSequence().map { sur(it) }.toSet()
        val names = ps.keys().asSequence().toList()
        val use = names.filter { starters.isEmpty() || sur(it) in starters }.ifEmpty { names }
        val strong = mutableSetOf<String>(); val weak = mutableSetOf<String>()
        for ((g, attrs) in GROUPS) {
            val vals = use.flatMap { n -> val p = ps.getJSONObject(n); attrs.mapNotNull { a -> if (p.has(a)) p.getInt(a) else null } }
            if (vals.isEmpty()) continue
            val avg = vals.average()
            if (avg >= 12.5) strong.add(g) else if (avg <= 10.0) weak.add(g)
        }
        return strong to weak
    }

    fun status(st: Store): String {
        val tac = st.obj("tactic"); val o = st.obj("opp")
        val pages = listOf("Team Mentality", "Defensive Line", "Final Third").count { tac.has(it) }
        fun m(ok: Boolean) = if (ok) "☑ " else "☐ "
        val lu = st.obj("lineup").length(); val pl = st.obj("players").length()
        return m(st.str("team").isNotBlank()) + "Team " + st.str("team") + "\n" +
            m(lu > 0) + "Lineup ($lu/11)\n" +
            m(pages == 3) + "Tactics ($pages/3: Shape, Defence, Attack)\n" +
            m(pl > 0) + "Players ($pl saved)\n" +
            m(o.length() > 0) + "Opponent " + o.optString("name")
    }

    private fun line(st: Store, g: String, v: String, why: String, multi: Boolean = false): String {
        val cur = st.obj("tactic").strs(g)
        val head = when {
            cur.isEmpty() -> "$g: $v"
            v in cur -> "$g: keep $v ✔"
            multi -> "$g: switch to $v"
            else -> "$g: change ${cur.joinToString("/")} ➜ $v"
        }
        return "• $head. $why"
    }

    private fun side(pos: String) = when {
        pos.isEmpty() -> ""
        pos.endsWith("L") -> "your right"
        pos.endsWith("R") -> "your left"
        else -> "the middle"
    }

    fun advise(st: Store): String {
        if (!st.d.has("opp") || st.obj("opp").length() == 0)
            return "No opponent data yet. Open the Pre-Match Hub or Next Match > Report, then tap Scan."
        val o = st.obj("opp")
        val os = o.strs("str").joinToString(" ").lowercase(); val ow = o.strs("wk").joinToString(" ").lowercase()
        val style = o.optString("style").lowercase(); val shape = o.optString("shape")
        val (strong, weak) = traits(st)
        val pace = "pace" in strong
        val oAtk = has(style, "attack", "overload"); val oDef = has(style, "defens", "contain")
        val oPaceS = has(os, "pace", "quick"); val oPaceW = has(ow, "pace", "slow")
        val oPassS = has(os, "passing", "technique", "technical", "creativ")
        val oPhysW = has(ow, "strength", "physical", "stamina", "fitness")
        val oStamW = has(ow, "stamina", "fitness", "tire")
        val oAirW = has(ow, "aerial", "heading", "in the air"); val oAirS = has(os, "aerial", "heading", "in the air")
        val oInj = has(ow, "injur"); val oMor = has(ow, "morale", "team spirit", "fragile", "confidence")
        val oDisc = has(ow, "disciplin", "cards", "booking")
        val oSetS = has(os, "set piece", "set-piece", "corner", "free kick")

        // SHAPE
        var risk = 3; val mw = mutableListOf<String>()
        if (oAtk) { risk--; mw.add("they play attacking and leave space") }
        if (oDef) { risk++; mw.add("they sit deep") }
        if (oMor || oInj) { risk++; mw.add("they look fragile (morale/injuries)") }
        if ((oPaceS || oPassS) && "defending" in weak) { risk--; mw.add("they are dangerous and your defending is weak") }
        var ment = listOf("Contain", "Defensive", "Counter", "Balanced", "Control", "Attacking")[risk.coerceIn(0, 5)]
        if (oAtk && pace && risk in 2..4) { ment = "Counter"; mw.add("your pace suits breaking quickly") }
        val wide = shape.startsWith("3") || shape.startsWith("5") || oDef || (oAirW && ("crossing" in strong || "aerial" in strong))
        val width = if (wide) "Wide" else if (oPassS) "Narrow" else "Balanced"
        val tempo = when {
            ment == "Contain" || ment == "Defensive" -> "Slow"
            ment == "Counter" || ment == "Attacking" || oStamW || oMor || oInj -> "Fast"
            else -> "Normal"
        }
        val creative = when {
            ("dribbling" in strong || "passing" in strong) && oDef -> "Expressive"
            "mental" in weak || ment == "Contain" || ment == "Defensive" -> "Disciplined"
            else -> "Balanced"
        }
        val shp = listOf(
            line(st, "Team Mentality", ment, mw.joinToString("; ").ifBlank { "balanced approach" }),
            line(st, "Width", width, if (wide) "stretch them (deep block, back three/five or weak in the air)" else if (oPassS) "keep it compact against their technical players" else "no strong reason to change"),
            line(st, "Passing Tempo", tempo, if (tempo == "Fast") "get at them quickly while they are vulnerable" else if (tempo == "Slow") "keep control and protect your shape" else "steady rhythm"),
            line(st, "Creative Freedom", creative, if (creative == "Expressive") "your quality can unlock a deep defence" else if (creative == "Disciplined") "stay organised" else "default balance")
        )

        // DEFENCE
        val line0 = when {
            oPaceW || (oDef && !oPaceS) || (pace && !oPaceS) -> "High"
            oPaceS && !pace -> "Deep"
            else -> "Balanced"
        }
        var cd = 0
        if (oPassS && "workrate" !in weak) cd++
        if (oMor || oInj || oStamW) cd++
        if ("workrate" in weak) cd--
        if (oPaceS) cd--
        val closing = if (cd >= 1) "All Over" else if (cd <= -1) "Sit Back" else "Own Half"
        var tk = 0
        if (oPhysW || oInj) tk++
        if (oPassS) tk--
        val tackling = if (tk >= 1) "Committed" else if (tk <= -1) "Cautious" else "Normal"
        val dfn = listOf(
            line(st, "Defensive Line", line0, if (line0 == "High") "they lack pace or sit deep, so squeeze them" else if (line0 == "Deep") "they have pace, so don't leave space behind" else "no clear edge either way"),
            line(st, "Closing Down", closing, if (closing == "All Over") "disrupt their passing while they are fragile" else if (closing == "Sit Back") "save energy and keep your shape" else "press in your own half"),
            line(st, "Tackling", tackling, if (tackling == "Committed") "they are physically weak" else if (tackling == "Cautious") "avoid being dribbled past and giving fouls" else "mixed picture"),
            line(st, "Offside Trap", if (line0 == "High" && !oPaceS) "Yes" else "No", "only worth it with a high line against a team without pace"),
            line(st, "Time Wasting", "No", "switch to Yes only when protecting a lead late on")
        )

        // ATTACK
        val r1 = if (oAirW && ("aerial" in strong || "crossing" in strong)) "Early Crosses" else "Look For Overlap"
        val r2 = if (oDef && "shooting" !in weak) "Shoot On Sight" else "Work Into Box"
        val r3 = if ("dribbling" in strong && (oPhysW || oPaceW || !(pace || "passing" in strong))) "Run At Defence" else "Through Balls"
        val pstyle = when {
            oAirW && "aerial" in strong -> "Long"
            ment == "Counter" || (oPaceW && pace) -> "Direct"
            "passing" in strong && ment in listOf("Control", "Attacking", "Balanced") -> "Short"
            else -> "Mixed"
        }
        val wpPos = o.optJSONObject("lineup")?.optString(o.optString("weak")) ?: ""
        val focus = when {
            wpPos.isEmpty() -> if (wide) "Both Flanks" else "Mixed"
            wpPos.endsWith("R") -> "Left"
            wpPos.endsWith("L") -> "Right"
            else -> "Centre"
        }
        val gk = when {
            (oAirW && "aerial" in strong) || (ment == "Counter" && oPaceW) -> "Long"
            "passing" in strong && ment != "Counter" -> "Short"
            else -> "Mixed"
        }
        val atk = listOf(
            line(st, "Final Third", r1, "crossing vs overlapping", true),
            line(st, "Final Third", r2, "shots vs patience", true),
            line(st, "Final Third", r3, "running vs passing at their back line", true),
            line(st, "Passing Style", pstyle, "fits your mentality and their weaknesses"),
            line(st, "Passing Focus", focus, if (wpPos.isNotEmpty()) "their weak point ${o.optString("weak")} plays $wpPos" else "no clear weak side"),
            line(st, "Goalkeeper Distribution", gk, "start attacks the way that suits your plan")
        )

        // SET PIECES
        val spc = mutableListOf<String>()
        if (oAirW) spc.add("• Corners/free kicks: they are weak in the air, so send 2 to 3 tall players Run Into Box and whip it in." + if ("aerial" in strong) " Your aerial strength helps." else "")
        else if (oAirS) spc.add("• Corners/free kicks: they are strong in the air, so use short routines and keep two defenders back.")
        else spc.add("• Corners/free kicks: mix near-post and far-post deliveries; send your best headers Run Into Box.")
        if (oDisc) spc.add("• They have a poor disciplinary record, so expect fouls near their box: set your best free-kick taker (check the Sho and Tec columns) and keep your own Tackling from adding cards.")
        spc.add("• Penalty takers: choose the player with the most Pen stars in the Takers list (not the goalkeeper).")
        spc.add("• Defending: " + if (oAirS || oSetS) "they are dangerous at set pieces, so keep your tallest defenders marking and avoid cheap fouls near your box." else "keep two defenders and your best header back.")

        // THREATS
        val thr = mutableListOf<String>()
        val key = o.optString("key"); val keyPos = o.optJSONObject("lineup")?.optString(key) ?: ""
        if (key.isNotBlank()) thr.add("• Key player $key${if (keyPos.isNotEmpty()) " ($keyPos)" else ""}${if (side(keyPos).isNotEmpty()) ": he threatens " + side(keyPos) + ", so protect that side" else ""}.")
        if (o.optString("weak").isNotBlank()) thr.add("• Weak point ${o.optString("weak")}${if (wpPos.isNotEmpty()) " ($wpPos)" else ""}: target and press him.")

        val sb = StringBuilder()
        sb.append("YOU: ${st.str("team").ifBlank { "(not scanned)" }} ${st.str("hubForm").ifBlank { st.str("derivedForm") }} ${st.str("myStyle")}\n")
        sb.append("OPPONENT: ${o.optString("name").ifBlank { "?" }} | ${o.optString("style")} | $shape\n")
        sb.append("Your strengths: ${strong.joinToString().ifBlank { "unknown (scan players)" }}\n")
        sb.append("Your weaknesses: ${weak.joinToString().ifBlank { "-" }}\n")
        listOf("SHAPE" to shp, "DEFENCE" to dfn, "ATTACK" to atk, "SET PIECES" to spc, "THREATS" to thr).forEach { (n, l) ->
            if (l.isNotEmpty()) sb.append("\n$n\n").append(l.joinToString("\n")).append("\n")
        }
        if (st.obj("players").length() < 6) sb.append("\nTip: scan more Player Profiles for sharper advice (${st.obj("players").length()} saved).")
        return sb.toString()
    }
}
