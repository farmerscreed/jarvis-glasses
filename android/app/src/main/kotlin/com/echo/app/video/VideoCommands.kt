package com.echo.app.video

/**
 * Voice phrases for glasses video (Phase 3 · V1, docs/PHASE3_VIDEO_DESIGN.md). Pure functions, tested in
 * VideoCommandsTest. "Jarvis, record a video" · "stop the video" · "start a site walk" · "stop the walk".
 */
object VideoCommands {
    private val START = Regex(
        """\b((record|take|start|film|shoot|make)\s+(a\s+|the\s+)?(video|clip)|start\s+(the\s+)?video(\s+recording)?|video\s+(this|that))\b""",
        RegexOption.IGNORE_CASE,
    )
    private val STOP = Regex("""\b(stop|end|finish)\s+(the\s+)?(video|filming|clip)\b""", RegexOption.IGNORE_CASE)
    private val START_WALK = Regex("""\b(start|begin|do)\s+(a\s+|the\s+|my\s+)?(site\s+|farm\s+)?walk\b""", RegexOption.IGNORE_CASE)
    private val STOP_WALK = Regex("""\b(stop|end|finish)\s+(the\s+|my\s+)?(site\s+|farm\s+)?walk\b""", RegexOption.IGNORE_CASE)

    fun isStart(s: String) = START.containsMatchIn(s) && !STOP.containsMatchIn(s)
    fun isStop(s: String) = STOP.containsMatchIn(s)
    fun isStartWalk(s: String) = START_WALK.containsMatchIn(s)
    fun isStopWalk(s: String) = STOP_WALK.containsMatchIn(s)
}
