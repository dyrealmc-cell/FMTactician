package com.fmtactician

/** Squad traits the user ticks in the app. */
val SQUAD_TRAITS = linkedMapOf(
    "pace" to "Quick wingers / forwards",
    "aerial" to "Tall, strong in the air",
    "passing" to "Good passers / technical midfield",
    "depth" to "Strong bench / high fitness",
    "setpieces" to "Good set-piece takers",
    "pressing" to "Energetic, high work rate"
)

private data class Rule(
    val pattern: Regex, val note: String, val advice: String,
    val trait: String? = null, val bonus: String? = null
)

// Keyword rules: edit/add freely to match FM26's exact scouting wording.
private val RULES = listOf(
    Rule(Regex("weak.{0,20}(in the air|aerial|heading)|poor.{0,10}(heading|jumping)"),
        "Weak in the air", "Whip in crosses, use a target man and wide overloads.", "aerial", "Your aerial strength makes this even better."),
    Rule(Regex("strong.{0,20}(in the air|aerial)"),
        "Strong in the air", "Avoid hopeful crosses; play on the floor, through the middle and via short passing."),
    Rule(Regex("slow|lack.{0,10}pace|poor.{0,10}pace|vulnerable.{0,15}(counter|behind|over the top)"),
        "Slow / exposed defence", "Play a higher line with through balls and counter-attack with direct runners.", "pace", "Your pacey forwards should exploit this."),
    Rule(Regex("(high|aggressive).{0,10}(press|line)|press.{0,10}(high|aggressive)"),
        "Presses high", "Play out quickly with short passes, or go long over the press. Use a lower tempo build-up to bait it.", "passing", "Your passers can beat the press."),
    Rule(Regex("(deep|low).{0,10}(block|line)|defensive|park the bus"),
        "Sits deep", "Be patient: wide play, overlaps, shots from distance and set pieces. Raise width and tempo.", "setpieces", "Set pieces will be a key weapon."),
    Rule(Regex("weak.{0,25}set.?piece|set.?piece.{0,20}weak|concede.{0,15}set.?piece"),
        "Poor at defending set pieces", "Win corners and free kicks: attack the flanks and take short throws.", "setpieces", "Use your best taker."),
    Rule(Regex("counter.?attack|direct|long ball"),
        "Dangerous on the counter", "Keep a holding midfielder, drop the defensive line slightly and avoid losing the ball in the middle."),
    Rule(Regex("tire|fatigue|stamina|low.{0,10}fitness"),
        "Stamina concerns", "Raise your pressing and tempo and sub fresh legs on after 60 mins.", "depth", "Your bench depth helps."),
    Rule(Regex("inexperienced|young|poor.{0,10}morale|low.{0,10}morale|out of form|losing"),
        "Shaky form / morale", "Start fast: high tempo and early goals can make them crumble.", "pressing", "Your high work rate fits.")
)

object TacticsEngine {
    fun advise(ocr: String, squad: Set<String>): String {
        val t = ocr.lowercase()
        val out = StringBuilder()
        Regex("\\b[2-5]-[1-5]-[1-5](-[1-5])?\\b").find(t)?.let { out.append("Opponent shape: ${it.value}\n\n") }
        val hits = RULES.filter { it.pattern.containsMatchIn(t) }
        if (hits.isEmpty()) return "Couldn't spot any strengths or weaknesses.\nOpen the opponent's scouting / analysis screen and tap again."
        out.append("Opponent notes:\n")
        hits.forEach { out.append("• ${it.note}\n") }
        out.append("\nSuggestions:\n")
        hits.forEach { r ->
            out.append("• ${r.advice}")
            if (r.trait != null && r.trait in squad && r.bonus != null) out.append(" ${r.bonus}")
            out.append("\n")
        }
        val pace = "pace" in squad; val pass = "passing" in squad
        out.append("\nSuggested base: ")
        out.append(when {
            pace && !pass -> "4-4-2 / 4-2-3-1 counter, direct passing, higher tempo."
            pass && !pace -> "4-3-3 possession, shorter passing, lower tempo, patient build-up."
            pace && pass -> "4-2-3-1 or 4-3-3 balanced, mixed passing, higher line."
            else -> "4-4-2 / 4-1-4-1 balanced, standard mentality."
        })
        return out.toString()
    }
}
