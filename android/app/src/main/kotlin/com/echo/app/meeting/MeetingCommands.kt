package com.echo.app.meeting

/**
 * Voice phrases for meetings (Phase 2 · M1). Pure functions, unit-tested in MeetingCommandsTest.
 *  "Jarvis, record this meeting (with Mr Ade)" · "stop recording" · "mark that — price agreed" ·
 *  "am I still recording?"
 */
object MeetingCommands {
    private val START = Regex(
        """\b(record (this|the|a|our) (meeting|conversation|discussion)|start (a |the )?(meeting )?recording|record a meeting|meeting mode)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val STOP = Regex("""\b(stop|end|finish) (the )?(recording|meeting)\b""", RegexOption.IGNORE_CASE)
    private val MARK = Regex("""\b(mark|bookmark|flag) (that|this|it)\b""", RegexOption.IGNORE_CASE)
    private val STATUS = Regex("""\b(am i|are you|is it|are we) (still )?recording\b""", RegexOption.IGNORE_CASE)

    fun isStart(s: String) = START.containsMatchIn(s)
    fun isStop(s: String) = STOP.containsMatchIn(s)
    fun isMark(s: String) = MARK.containsMatchIn(s)
    fun isStatus(s: String) = STATUS.containsMatchIn(s)

    /** "mark that — price agreed" / "mark that: price agreed" → "price agreed" ("" if none). */
    fun markLabel(s: String): String {
        val m = MARK.find(s) ?: return ""
        return s.substring(m.range.last + 1).trim().trimStart('-', '—', ':', ',', '.', ' ').trim().trimEnd('.')
    }

    /** "record this meeting with Mr Ade about cement" → "Meeting with Mr Ade about cement" ("" if no detail). */
    fun title(s: String): String {
        val m = START.find(s) ?: return ""
        val rest = s.substring(m.range.last + 1).trim().trimStart(',', '.', ' ').trimEnd('.', '!', '?')
        return if (rest.length > 3) "Meeting " + rest.removePrefix("meeting ").trim() else ""
    }
}
