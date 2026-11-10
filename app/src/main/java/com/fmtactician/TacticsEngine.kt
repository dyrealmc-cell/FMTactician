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
            "possession" in l && "clear cut" in l -> "match"
            "action zones" in l || "attack areas" in l -> "matchgfx"
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

    private fun rowRight(s: Scan, label: String): String {
        val lb = s.lines.firstOrNull { it.t.equals(label, true) } ?: return ""
        return s.lines.filter { it !== lb && it.x > lb.x + lb.w && Math.abs(it.cy - lb.cy) < lb.h * 0.7 }
            .minByOrNull { it.x }?.t ?: ""
    }

    private fun suits(a: JSONObject): String {
        fun v(k: String) = a.optInt(k, 0)
        val out = mutableListOf<String>()
        if (v("Pace") >= 13 && v("Dribbling") >= 12) out.add("Inside Forward / Winger")
        if (v("Strength") >= 13 && v("Aerial") >= 12 && v("Shooting") >= 12) out.add("Target Forward")
        if (v("Stamina") >= 13 && v("Aggression") >= 12) out.add("Box-to-Box / Ball Winner")
        if (v("Passing") >= 13 && v("Technique") >= 12) out.add("Playmaker / Passer")
        if (v("Tackling") >= 12 && v("Positioning") >= 12) out.add("Defensive Midfielder / No-Nonsense CB")
        if (v("Crossing") >= 12 && v("Pace") >= 12) out.add("Full Back (Support) / wide creator")
        return out.joinToString()
    }

    fun player(s: Scan, st: Store): String {
        val club = st.str("team").substringBefore(' ')
        if (club.length >= 3 && !s.text.contains(club, true))
            return "Skipped: this doesn't look like a ${st.str("team")} player."
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
        val age = s.lines.firstNotNullOfOrNull { Regex("^(\\d{2}) yrs?$").find(it.t)?.groupValues?.get(1)?.toInt() }
        age?.let { attrs.put("_age", it) }
        val cond = rowRight(s, "Condition"); val mor = rowRight(s, "Morale"); val rat = rowRight(s, "Av. Rating")
        if (cond.isNotBlank()) attrs.put("_cond", cond)
        if (mor.isNotBlank()) attrs.put("_morale", mor)
        if (rat.isNotBlank()) attrs.put("_rating", rat)
        st.obj("players").put(name, attrs)
        val list = attrs.keys().asSequence().filter { !it.startsWith("_") }.map { it to attrs.getInt(it) }.toList()
        val str = list.filter { it.second >= 13 }.sortedByDescending { it.second }.joinToString { "${it.first} ${it.second}" }
        val wk = list.filter { it.second <= 9 }.sortedBy { it.second }.joinToString { "${it.first} ${it.second}" }
        val su = suits(attrs)
        return "PLAYER saved: $name${age?.let { " ($it)" } ?: ""}\nStrengths: ${str.ifBlank { "-" }}\nWeaknesses: ${wk.ifBlank { "-" }}" +
            (if (cond.isNotBlank() || mor.isNotBlank()) "\nCondition ${cond.ifBlank { "?" }}, morale ${mor.ifBlank { "?" }}" else "") +
            (if (su.isNotBlank()) "\nSuits: $su" else "")
    }

    private fun num(t: String) = Regex("\\d+(\\.\\d+)?").find(t)?.value?.toDoubleOrNull() ?: 0.0
    private fun sot(t: String) = Regex("\\((\\d+)\\)").find(t)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

    private fun statRow(s: Scan, label: String): Pair<String, String>? {
        val lb = s.lines.firstOrNull { it.t.equals(label, true) } ?: return null
        val row = s.lines.filter { it !== lb && Math.abs(it.cy - lb.cy) < lb.h * 0.9 }
        val l = row.filter { it.cx < lb.cx }.maxByOrNull { it.cx } ?: return null
        val r = row.filter { it.cx > lb.cx }.minByOrNull { it.cx } ?: return null
        return l.t to r.t
    }

    /** Live match advice from the match Stats overview. */
    fun match(s: Scan, st: Store): String {
        val top = s.lines.filter { it.y < s.h * 0.1 }
        val minute = top.firstNotNullOfOrNull { Regex("^(\\d{1,3}):\\d{2}$").find(it.t)?.groupValues?.get(1)?.toInt() } ?: -1
        val abbr = top.filter { Regex("^[A-Z]{3}$").matches(it.t) }.sortedBy { it.x }
        val sc = if (abbr.size >= 2) top.filter { Regex("^\\d{1,2}$").matches(it.t) && it.cx > abbr[0].cx && it.cx < abbr[1].cx }.sortedBy { it.x } else emptyList()
        val ours = st.str("team").take(3).uppercase()
        val weLeft = !(abbr.size >= 2 && abbr[1].t == ours && abbr[0].t != ours)
        val gl = sc.getOrNull(0)?.t?.toIntOrNull() ?: 0; val gr = sc.getOrNull(1)?.t?.toIntOrNull() ?: 0
        val gm = if (weLeft) gl else gr; val gt = if (weLeft) gr else gl
        val poss = statRow(s, "POSSESSION"); val ccc = statRow(s, "CLEAR CUT CHANCES"); val rate = statRow(s, "TEAM RATING")
        val shots = statRow(s, "SHOTS (ON TARGET)"); val corn = statRow(s, "CORNERS")
        if (poss == null && shots == null) return "Couldn't read the match stats. Open the match Stats overview (possession, shots) and tap Scan."
        fun mine(p: Pair<String, String>?) = if (p == null) "" else if (weLeft) p.first else p.second
        fun theirs(p: Pair<String, String>?) = if (p == null) "" else if (weLeft) p.second else p.first
        val pm = num(mine(poss)); val cm = num(mine(ccc)); val ct = num(theirs(ccc))
        val sm = num(mine(shots)); val sotM = sot(mine(shots)); val cr = num(mine(corn))
        val rm = num(mine(rate)); val rt = num(theirs(rate))
        val adv = mutableListOf<String>()
        val d = gm - gt
        if (d < 0) adv.add("Losing: ${if (minute >= 60) "bring on fresh attackers now, " else ""}raise Team Mentality one step (for example Balanced ➜ Control or Attacking) and set Passing Tempo to Fast.")
        if (d > 0 && minute >= 65) adv.add("Protect the lead: go one step safer on Team Mentality, Time Wasting Yes, Defensive Line Balanced or Deep, and bring on a passer or fresh midfielder to keep the ball.")
        if (d == 0 && minute >= 60) adv.add("Level late: bring fresh legs on around 60-65 minutes and use Passing Tempo Fast if their fitness is poor.")
        if (ct >= 3 && ct > cm + 1) adv.add("They are creating better chances ($ct clear-cut vs ${cm.toInt()}): add a holding midfielder, use Closing Down Own Half and keep the Defensive Line Balanced.")
        if (pm in 1.0..44.0 && ct >= cm) adv.add("You are being outplayed in possession: use Passing Style Short or Mixed and Tempo Normal to keep the ball, or stay Counter and defend deeper.")
        if (sm >= 6 && sotM * 2 < sm) adv.add("Many of your shots are off target: switch Final Third from Shoot On Sight to Work Into Box.")
        if (sm <= 2 && minute >= 30 && pm >= 50) adv.add("You have the ball but few shots: use Through Balls and Run At Defence and add a runner between the lines.")
        if (cm > ct + 1 && d <= 0) adv.add("You are creating the chances but not scoring: stay with the plan and look for rebounds and set pieces.")
        if (rt - rm >= 0.4) adv.add("Their players are rating higher: check the squad screen for tired or poor-rating players to swap.")
        if (cr >= 4) adv.add("You are winning many corners: send your best headers Run Into Box.")
        if (adv.isEmpty()) adv.add("No clear problem in the numbers. Keep your plan and make fresh-legs changes around 60-65 minutes.")
        return "LIVE ${if (minute >= 0) "$minute'" else ""} score $gm-$gt${if (abbr.size >= 2 && ours != abbr[0].t && ours != abbr[1].t) " (assuming you are the left team)" else ""}\n" +
            "Possession ${pm.toInt()}%, shots ${sm.toInt()} (${sotM.toInt()} on target), clear-cut ${cm.toInt()} vs ${ct.toInt()}\n\n" +
            adv.joinToString("\n") { "• $it" }
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

    private fun top(st: Store, a: String, n: Int = 2): String {
        val ps = st.obj("players")
        val l = ps.keys().asSequence().filter { ps.getJSONObject(it).has(a) }.map { it to ps.getJSONObject(it).getInt(a) }
            .filter { it.second >= 12 }.sortedByDescending { it.second }.take(n).toList()
        return if (l.isEmpty()) "" else " Best: " + l.joinToString { "${it.first} ($a ${it.second})" } + "."
    }

    private fun squadCheck(st: Store): List<String> {
        val ps = st.obj("players"); val out = mutableListOf<String>()
        ps.keys().asSequence().toList().forEach { n ->
            val p = ps.getJSONObject(n)
            val cond = p.optString("_cond").filter { it.isDigit() }.toIntOrNull()
            if (cond != null && cond < 85) out.add("• $n condition $cond%: rest or sub early.")
            if (p.optString("_morale").lowercase().contains("poor")) out.add("• $n morale ${p.optString("_morale")}: may underperform.")
            if (p.optInt("_age", 0) >= 33) out.add("• $n is ${p.optInt("_age")}: sub around 65-70 minutes.")
            else if (p.has("Stamina") && p.getInt("Stamina") <= 9) out.add("• $n Stamina ${p.getInt("Stamina")}: sub around 60-65 minutes.")
        }
        return out.take(8)
    }

    fun advise(st: Store): String {
        if (!st.d.has("opp") || st.obj("opp").length() == 0)
            return "No opponent data yet. Open the Pre-Match Hub or Next Match > Report, then tap Scan."
        val o = st.obj("opp")
        val os = o.strs("str").joinToString(" ").lowercase(); val ow = o.strs("wk").joinToString(" ").lowercase()
        val style = o.optString("style").lowercase(); val shape = o.optString("shape")
        val (strong, weak) = traits(st)
        val pace = "pace" in strong
        // Opponent weaknesses
        val oPaceW = has(ow, "pace", "slow"); val oPassW = has(ow, "passing", "technique"); val oDecW = has(ow, "decision", "composure", "temperament")
        val oFitW = has(ow, "stamina", "fitness", "tire", "energy"); val oPhysW = has(ow, "strength", "physical")
        val oAirW = has(ow, "aerial", "heading", "in the air"); val oInj = has(ow, "injur")
        val oMor = has(ow, "morale", "team spirit", "fragile", "confidence")
        val oGkW = has(ow, "goalkeep", "keeper"); val oGoalW = has(ow, "goalscor", "scorer", "goals", "finishing")
        val oLeadW = has(ow, "leadership"); val oDisc = has(ow, "disciplin", "cards", "booking")
        // Opponent strengths
        val oPaceS = has(os, "pace", "quick"); val oCreS = has(os, "creativ"); val oPassS = has(os, "passing"); val oTechS = has(os, "technique", "technical")
        val oDecS = has(os, "decision", "temperament", "composure"); val oFitS = has(os, "stamina", "fitness", "energy", "work rate")
        val oFormS = has(os, "form", "morale", "confidence", "team spirit"); val oYouthS = has(os, "young", "youth")
        val oAirS = has(os, "aerial", "heading", "in the air", "target"); val oGkS = has(os, "goalkeep", "keeper")
        val oDefS = has(os, "defensive record", "conceded", "clean sheet", "solid"); val oSetS = has(os, "set piece", "set-piece", "corner", "free kick")
        val oAtk = has(style, "attack", "overload"); val oDef = has(style, "defens", "contain")

        // SHAPE
        var risk = 3; val mw = mutableListOf<String>()
        if (oAtk) { risk--; mw.add("they play attacking and leave space") }
        if (oDef) { risk++; mw.add("they sit deep") }
        if (oMor || oInj) { risk++; mw.add("they look fragile (morale/injuries), so an early goal can deflate them") }
        if (oFormS) { risk = minOf(risk, 4); mw.add("they are in good form: start controlled and push after 60 minutes") }
        if ((oPaceS || oPassS || oCreS) && "defending" in weak) { risk--; mw.add("they are dangerous and your defending is weak") }
        var ment = listOf("Contain", "Defensive", "Counter", "Balanced", "Control", "Attacking")[risk.coerceIn(0, 5)]
        if (oAtk && pace && risk in 2..4) { ment = "Counter"; mw.add("your pace suits breaking quickly") }
        val wide = shape.startsWith("3") || shape.startsWith("5") || oDef || (oAirW && ("crossing" in strong || "aerial" in strong))
        val width = if (wide) "Wide" else if (oPassS || oCreS) "Narrow" else "Balanced"
        val tempo = when {
            ment == "Contain" || ment == "Defensive" -> "Slow"
            oFitW -> "Normal"
            oMor && !oInj -> "Normal"
            oPassW || oInj || ment == "Counter" || ment == "Attacking" -> "Fast"
            else -> "Normal"
        }
        val tWhy = when {
            tempo == "Slow" -> "keep control and protect your shape"
            oFitW -> "their fitness is poor: start Normal, switch to Fast around 60 minutes"
            oMor && !oInj -> "start controlled and go for the opening goal"
            tempo == "Fast" -> "they lose the ball under pressure or are short of players, so attack quickly"
            else -> "steady rhythm"
        }
        val creative = when {
            ("dribbling" in strong || "passing" in strong) && oDef -> "Expressive"
            "mental" in weak || ment == "Contain" || ment == "Defensive" -> "Disciplined"
            else -> "Balanced"
        }
        val shp = listOf(
            line(st, "Team Mentality", ment, mw.joinToString("; ").ifBlank { "balanced approach" }),
            line(st, "Width", width, if (wide) "stretch them (deep block, back three/five or weak in the air)" else if (oPassS || oCreS) "stay compact against their quality in midfield" else "no strong reason to change"),
            line(st, "Passing Tempo", tempo, tWhy),
            line(st, "Creative Freedom", creative, if (creative == "Expressive") "your quality can unlock a deep defence" else if (creative == "Disciplined") "stay organised" else "default balance")
        )

        // DEFENCE
        val line0 = when {
            oPaceW && "pace" in weak -> "Balanced"
            oPaceW || oGoalW || (oDef && !oPaceS) || (pace && !oPaceS) -> "High"
            oPaceS && !pace -> "Deep"
            else -> "Balanced"
        }
        var cd = 0
        if (oPassW || oDecW || oPassS) cd++
        if (oMor || oInj || oFitW) cd++
        if ("workrate" in weak) cd--
        if (oPaceS) cd--
        if (oCreS) cd = minOf(cd, 0)
        val closing = if (cd >= 1) "All Over" else if (cd <= -1) "Sit Back" else "Own Half"
        val tackling = if (oTechS || oDecW || oPassS) "Normal" else if (oPhysW) "Committed" else "Normal"
        val offside = if (line0 == "High" && !oPaceS && pace) "Yes" else "No"
        val dfn = listOf(
            line(st, "Defensive Line", line0, if (line0 == "High") "they are slow, scarce on goals or sit deep, so squeeze them" else if (line0 == "Deep") "they have pace, so don't leave space behind" else "no clear edge either way"),
            line(st, "Closing Down", closing, (if (closing == "All Over") "win the ball high: they lose it under pressure or are fragile." + top(st, "Aggression") else if (closing == "Sit Back") "save energy and keep your shape" else if (oCreS) "close down their playmaker in your own half" else "press in your own half")),
            line(st, "Tackling", tackling, if (tackling == "Committed") "they are physically weak" else if (oTechS || oPassS) "don't dive in against technical players; press as a team" else "don't dive in; keep Tackling Normal"),
            line(st, "Offside Trap", offside, "only worth it with a high line, quick defenders and a team without pace"),
            line(st, "Time Wasting", "No", "switch to Yes only when protecting a lead late on")
        )

        // ATTACK
        val r1 = if (oAirW && ("aerial" in strong || "crossing" in strong) && !oDefS) "Early Crosses" else "Look For Overlap"
        val r2 = if (oGkS) "Work Into Box" else if (oGkW || (oDef && "shooting" !in weak)) "Shoot On Sight" else "Work Into Box"
        val r3 = if (!oDefS && "dribbling" in strong && (oPhysW || oPaceW || !(pace || "passing" in strong))) "Run At Defence" else "Through Balls"
        val pstyle = when {
            oAirW && "aerial" in strong -> "Long"
            ment == "Counter" || (oPaceW && pace) -> "Direct"
            oAirW || oPhysW -> if ("aerial" in strong) "Direct" else "Mixed"
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
            line(st, "Final Third", r1, if (r1 == "Early Crosses") "they are weak in the air." + top(st, "Aerial") else if (oDefS) "strong defensive record: don't cross early, build with overlaps" else "build wide play with overlaps", true),
            line(st, "Final Third", r2, if (r2 == "Shoot On Sight") "weak goalkeeper or deep block: shots are worth taking." + top(st, "Shooting") else if (oGkS) "good goalkeeper: don't rely on long shots, aim for rebounds" else "work the ball into the box", true),
            line(st, "Final Third", r3, if (r3 == "Run At Defence") "run at them." + top(st, "Dribbling") else if (oPaceW) "they are slow: play in behind." + top(st, "Pace") else "runs from deep and passes behind the line", true),
            line(st, "Passing Style", pstyle, if (pstyle == "Short") "keep the ball with your passers." + top(st, "Passing") else "fits your mentality and their weaknesses"),
            line(st, "Passing Focus", focus, if (wpPos.isNotEmpty()) "their weak point ${o.optString("weak")} plays $wpPos" else "no clear weak side"),
            line(st, "Goalkeeper Distribution", gk, "start attacks the way that suits your plan")
        )

        // SET PIECES
        val spc = mutableListOf<String>()
        if (oAirW) spc.add("• Corners and wide free kicks: they are weak in the air, so send 2 to 3 tall players Run Into Box and whip it in." + top(st, "Aerial"))
        else if (oAirS) spc.add("• They are strong in the air: use short routines and keep two defenders back.")
        else spc.add("• Corners/free kicks: mix near-post and far-post deliveries and send your best headers Run Into Box.")
        if (oGkW) spc.add("• Weak goalkeeper: work rebounds from corners.")
        if (oDecS) spc.add("• They rarely make mistakes: set pieces and goals from rebounds matter, so be patient.")
        if (oDisc) spc.add("• Poor disciplinary record: expect fouls near their box, so set your best free-kick taker." + top(st, "Shooting", 1))
        spc.add("• Penalty takers: choose the player with the most Pen stars in the Takers list (not the goalkeeper).")
        spc.add("• Defending: " + if (oAirS || oSetS) "man-mark their target man with your tallest defender and cut service from the wings." + top(st, "Aerial", 1) else "keep two defenders and your best header back.")

        // GAME PLAN (timing and mindset)
        val gp = mutableListOf<String>()
        if (oFitW) gp.add("• Their fitness is poor: start Normal tempo, switch to Fast around 60 minutes and make fresh subs count after 65.")
        if (oFitS) gp.add("• They are fit: match their tempo early, protect your stamina and rotate fresh players on by 60-70 minutes.")
        if (oFormS) gp.add("• Good form and high morale: don't go behind early. Start Balanced or Control, then become more aggressive after 60 minutes.")
        if (oMor) gp.add("• Low morale: score first, since an early goal can deflate them.")
        if (oInj) gp.add("• Injuries: their replacements are weaker, so push the tempo and keep pressing; tired legs show late.")
        if (oYouthS) gp.add("• Young and energetic: use experience and shape, and don't let it become end-to-end.")
        if (oDecS) gp.add("• Be patient: wait for a gap rather than forcing passes.")
        if (oLeadW) gp.add("• Weak leadership: keep the game open late; if you score they struggle to recover.")
        if (oGoalW) gp.add("• Low goal threat: your fullbacks can push up safely, but a draw is likely unless you take your chances.")
        if (oCreS) gp.add("• Creative: stay compact, close down their playmaker and use tight marking on their creator.")
        if (oPaceS) gp.add("• Pace: keep a holding midfielder and keep your slow defenders covered.")
        if (oDefS) gp.add("• Strong defence: patient build-up, runs from deep and through balls rather than early crosses.")
        if (oTechS) gp.add("• Technical: don't dive into tackles; stay on your feet and press as a team.")

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
        listOf("SHAPE" to shp, "DEFENCE" to dfn, "ATTACK" to atk, "SET PIECES" to spc, "GAME PLAN" to gp, "THREATS" to thr, "SQUAD CHECK" to squadCheck(st)).forEach { (n, l) ->
            if (l.isNotEmpty()) sb.append("\n$n\n").append(l.joinToString("\n")).append("\n")
        }
        if (os.isBlank() && ow.isBlank()) sb.append("\nNo strengths or weaknesses read yet: scan the Hub and the Report.")
        if (st.obj("players").length() < 6) sb.append("\nTip: browse more Player Profiles so the app can learn your squad (${st.obj("players").length()} saved).")
        return sb.toString()
    }
}
