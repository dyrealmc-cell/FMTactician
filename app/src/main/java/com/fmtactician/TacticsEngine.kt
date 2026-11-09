package com.fmtactician

import android.content.Context
import org.json.JSONObject

data class Ln(val t: String, val h: Int, val y: Int)

/** Saved data about the user's own team, lineup and players. */
class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("fmdata", Context.MODE_PRIVATE)
    private fun s(k: String) = sp.getString(k, "") ?: ""
    private fun put(k: String, v: String) { sp.edit().putString(k, v).apply() }
    private fun list(k: String) = s(k).split("|").filter { it.isNotBlank() }
    var team: String
        get() = s("team")
        set(v) { put("team", v) }
    var formation: String
        get() = s("formation")
        set(v) { put("formation", v) }
    var lineup: List<String>
        get() = list("lineup")
        set(v) { put("lineup", v.joinToString("|")) }
    var teamStr: List<String>
        get() = list("tstr")
        set(v) { put("tstr", v.joinToString("|")) }
    var teamWeak: List<String>
        get() = list("tweak")
        set(v) { put("tweak", v.joinToString("|")) }
    fun players() = JSONObject(s("players").ifBlank { "{}" })
    fun savePlayer(n: String, st: List<String>, wk: List<String>) {
        val p = players()
        p.put(n, JSONObject().put("s", st.joinToString("|")).put("w", wk.joinToString("|")))
        put("players", p.toString())
    }
    fun all(key: String, base: List<String>): List<String> {
        val p = players(); val out = base.toMutableList()
        p.keys().forEach { n -> out += p.getJSONObject(n).getString(key).split("|").filter { it.isNotBlank() } }
        return out
    }
    fun clear() { sp.edit().clear().apply() }
}

private class Rule(
    val p: Regex, val note: String, val a: String = "", val d: String = "",
    val s: String = "", val sp: String = "", val trait: String = "", val bonus: String = ""
)

object Engine {
    private val STOP = setOf("strengths", "weaknesses", "tactics", "squad", "overview", "report", "fixtures",
        "players", "training", "scouting", "analysis", "statistics", "match", "team", "lineup", "back", "next",
        "continue", "formation", "attack", "defence", "shape", "set pieces", "mentality", "position", "contract")
    private val NAME = Regex("^[A-Za-z][A-Za-z .'’-]{2,27}$")
    private val NAME2 = Regex("^[A-Z][A-Za-z'’.-]+( [A-Z][A-Za-z'’.-]+){0,2}$")
    private val SW_OK = Regex("^[A-Za-z][A-Za-z '’/-]{2,29}$")

    private val TR = mapOf(
        "pace" to listOf("pace", "acceleration", "quick"),
        "aerial" to listOf("heading", "jumping", "aerial"),
        "passing" to listOf("passing", "vision", "technique", "first touch"),
        "setpieces" to listOf("free kick", "corner", "penalt", "set piece"),
        "pressing" to listOf("work rate", "stamina", "aggression", "pressing", "fitness")
    )
    private fun count(l: List<String>, k: String) = l.count { s -> TR[k]!!.any { s.lowercase().contains(it) } }

    // Keyword rules: tweak these to match the exact wording FM26 Mobile shows.
    private val RULES = listOf(
        Rule(Regex("weak.{0,20}(in the air|aerial|heading)|poor.{0,10}(heading|jumping)"), "Weak in the air",
            a = "Cross often from wide and aim for the far post with a target man.",
            s = "Use two up front or a tall striker.", sp = "Deliver corners and free kicks high into the box.",
            trait = "aerial", bonus = "Your aerial strength makes this even better."),
        Rule(Regex("strong.{0,20}(in the air|aerial)"), "Strong in the air",
            a = "Keep crosses low and play through the middle.", d = "Mark their tall players tightly.",
            sp = "Defend corners with zonal plus man marking; try short corner routines."),
        Rule(Regex("slow|lack.{0,10}pace|poor.{0,10}pace|vulnerable.{0,15}(counter|behind|over the top)"), "Slow or exposed defence",
            a = "Play direct through balls and counter at speed.", d = "Push the defensive line higher and press.",
            s = "4-3-3 with quick wide forwards.", trait = "pace", bonus = "Your pacey players should exploit this."),
        Rule(Regex("(high|aggressive).{0,10}(press|line)|press.{0,10}(high|aggressive)"), "Presses high",
            a = "Lower the tempo and play short from the back, or go long over the press.",
            d = "Stay compact and drop the line of engagement.", s = "Add a ball-playing midfielder (4-2-3-1).",
            trait = "passing", bonus = "Your passers can beat their press."),
        Rule(Regex("(deep|low).{0,10}(block|line)|defensive|park the bus"), "Sits deep",
            a = "Raise tempo and width, use overlaps, shoot from distance and stay patient.",
            d = "Hold a higher line and counter-press when you lose the ball.", s = "4-3-3 or 4-2-3-1 with a wide overload.",
            sp = "Win corners and free kicks; mix short corners with crosses.", trait = "setpieces", bonus = "Set pieces will be a key weapon."),
        Rule(Regex("weak.{0,25}set.?piece|set.?piece.{0,20}weak|concede.{0,15}set.?piece"), "Poor at defending set pieces",
            a = "Draw fouls on the edge of the box.", sp = "Aim corners at the near post, use flick-ons and short corners.",
            trait = "setpieces", bonus = "Use your best taker."),
        Rule(Regex("(strong|dangerous|good).{0,25}set.?piece"), "Dangerous at set pieces",
            d = "Avoid fouls near your box and don't give away cheap corners.",
            sp = "Defend with zonal marking plus a man on their best header."),
        Rule(Regex("counter.?attack|long ball"), "Dangerous on the counter",
            a = "Don't commit too many men forward and keep a holding midfielder.",
            d = "Hold a medium-low line and stay compact.", s = "4-2-3-1 with two holding midfielders."),
        Rule(Regex("tire|fatigue|stamina|low.{0,10}fitness"), "Stamina concerns",
            a = "Raise tempo to wear them down.", d = "Press high and often, and sub in fresh legs after 60 minutes.",
            trait = "pressing", bonus = "Your work rate fits this plan."),
        Rule(Regex("inexperienced|young|poor.{0,10}morale|low.{0,10}morale|out of form|losing"), "Shaky form or morale",
            a = "Start fast with high tempo to get an early goal.", d = "Press aggressively early.",
            trait = "pressing", bonus = "Your work rate fits."),
        Rule(Regex("clinical|prolific|strong.{0,15}attack|dangerous.{0,15}forward"), "Strong attack",
            d = "Sit a little deeper, protect the box and double up on their key attacker."),
        Rule(Regex("weak.{0,15}defen|leaky|concede"), "Leaky defence",
            a = "Attack quickly, shoot often and commit more players forward."),
        Rule(Regex("possession|strong.{0,15}midfield"), "Strong in midfield",
            d = "Press their playmaker and keep your midfield narrow.", s = "Add a midfielder (4-3-3 or 4-1-4-1) to compete for the ball.")
    )

    fun formation(t: String) = Regex("\\b[2-5]-[1-5](-[1-5]){1,3}\\b").find(t)?.value ?: ""

    fun guessName(l: List<Ln>, sh: Int): String =
        l.filter { it.y < sh * 0.3 && NAME.matches(it.t) && it.t.lowercase() !in STOP }.maxByOrNull { it.h }?.t ?: ""

    fun names(l: List<Ln>): List<String> =
        l.map { it.t }.filter { NAME2.matches(it) && it.length >= 4 && it.lowercase() !in STOP }.distinct().take(16)

    fun sw(text: String): Pair<List<String>, List<String>> {
        val s = mutableListOf<String>(); val w = mutableListOf<String>(); var mode = 0
        for (raw in text.lines()) {
            val x = raw.trim(); val lx = x.lowercase()
            if (lx.startsWith("strength")) { mode = 1; continue }
            if (lx.startsWith("weakness")) { mode = 2; continue }
            if (mode > 0 && SW_OK.matches(x) && lx !in STOP) {
                val t = if (mode == 1) s else w
                if (t.size < 8) t.add(x)
            }
        }
        return s to w
    }

    private fun counter(o: String) = when {
        o.startsWith("3") || o.startsWith("5") -> "4-3-3 with wide forwards to stretch their back line"
        o.startsWith("4-3-3") -> "4-2-3-1 to cover their midfield and wide players"
        o.startsWith("4-4-2") -> "4-3-3 to win the midfield battle"
        o.startsWith("4-2-3-1") -> "4-3-1-2 or a 4-4-2 diamond to crowd their number 10"
        else -> "a balanced 4-4-2 or 4-2-3-1"
    }

    fun advise(text: String, lines: List<Ln>, sh: Int, st: Store): String {
        val t = text.lowercase()
        val str = st.all("s", st.teamStr); val wk = st.all("w", st.teamWeak)
        val strong = TR.keys.filter { count(str, it) > count(wk, it) }
        val weak = TR.keys.filter { count(wk, it) > count(str, it) }
        val opp = guessName(lines, sh); val shape = formation(t)
        val hits = RULES.filter { it.p.containsMatchIn(t) }
        if (hits.isEmpty() && shape.isEmpty())
            return "Couldn't spot the opponent's strengths or weaknesses.\nOpen their report or analysis screen and tap Opponent again."
        val atk = mutableListOf<String>(); val def = mutableListOf<String>()
        val shp = mutableListOf<String>(); val spc = mutableListOf<String>()
        for (r in hits) {
            val bonus = if (r.trait in strong && r.bonus.isNotEmpty()) " " + r.bonus else ""
            if (r.a.isNotEmpty()) atk.add(r.a + (if (r.trait != "setpieces") bonus else ""))
            if (r.d.isNotEmpty()) def.add(r.d)
            if (r.s.isNotEmpty()) shp.add(r.s)
            if (r.sp.isNotEmpty()) spc.add(r.sp + (if (r.trait == "setpieces") bonus else ""))
        }
        if ("pace" in weak && hits.any { it.note.startsWith("Slow") })
            def.add("Your side lacks pace, so keep the defensive line standard rather than very high.")
        if ("passing" in weak && hits.any { it.note.startsWith("Presses") })
            atk.add("Passing looks like a weakness for you, so go more direct instead of playing out under their press.")
        if (shape.isNotEmpty()) shp.add(0, "They play $shape: try ${counter(shape)}.")
        if (st.formation.isNotEmpty()) shp.add(0, "You currently play ${st.formation}.")
        val sb = StringBuilder()
        sb.append("You: ${st.team.ifBlank { "(scan My Team first)" }} ${st.formation}\n")
        sb.append("Opponent: ${opp.ifBlank { "?" }} $shape\n")
        if (hits.isNotEmpty()) { sb.append("\nOPPONENT NOTES\n"); hits.forEach { sb.append("• ${it.note}\n") } }
        listOf("ATTACK" to atk, "DEFENCE" to def, "SHAPE" to shp, "SET PIECES" to spc).forEach { (n, l) ->
            sb.append("\n$n\n")
            if (l.isEmpty()) sb.append("• Nothing specific, keep your normal setup.\n")
            else l.distinct().forEach { sb.append("• $it\n") }
        }
        return sb.toString()
    }
}
