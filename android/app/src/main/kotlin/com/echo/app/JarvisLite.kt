package com.echo.app

import com.echo.core.model.Memory
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Jarvis Lite — the always-present, no-network answer floor (Phase C §4.3, option 2). Turns
 * off-grid recall + a few high-value intents into a natural spoken answer, so off-grid Jarvis
 * *answers* instead of dumping a raw memory. This is the rule-based floor that works with no
 * download; the optional large on-device LLM ("Offline Pack") would later replace this with
 * free-form reasoning, but is not required for the product to be offline-complete.
 */
object JarvisLite {

    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a")

    /** Compose an off-grid answer. [recalled] is the on-device semantic search result (best first). */
    fun answer(question: String, recalled: List<Memory>, now: LocalTime = LocalTime.now()): String {
        val q = question.trim().lowercase()

        // Simple intents that need no memory.
        if (q.contains("what time") || q == "time" || q.contains("the time")) {
            return "It's ${now.format(timeFmt)}. (I'm off-grid, so that's from your phone's clock.)"
        }

        val top = recalled.firstOrNull()?.text?.takeIf { it.isNotBlank() }
            ?: return "I'm off-grid and couldn't find anything about that in your memories. " +
                "I'll think it through properly once we're back online."

        val lead = when {
            q.startsWith("where") -> "Off-grid, but going by what you saved:"
            q.startsWith("when") -> "Off-grid — the closest thing you noted:"
            q.startsWith("who") || q.startsWith("what") -> "I can't reason it through off-grid, but you noted:"
            else -> "Off-grid — the closest memory I have is:"
        }
        val answer = StringBuilder("$lead $top")
        if (recalled.size > 1) {
            recalled[1].text?.takeIf { it.isNotBlank() }?.let { answer.append("\n\nAlso related: $it") }
        }
        answer.append("\n\nI'll give you a fuller answer when we're back online.")
        return answer.toString()
    }
}
