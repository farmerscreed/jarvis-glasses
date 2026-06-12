package com.echo.app

/**
 * Splits a token stream into spoken sentences as it arrives, so TTS can start on sentence 1 while
 * the LLM is still generating sentence 2+ (Phase D — time-to-first-sentence). A sentence is emitted
 * when a terminator (. ! ? : newline) lands and the buffer is long enough to not be an abbreviation.
 */
class SentenceChunker(private val minLen: Int = 12, private val onSentence: (String) -> Unit) {
    private val buf = StringBuilder()

    fun add(text: String) {
        buf.append(text)
        var cut = boundary()
        while (cut > 0) {
            val sentence = buf.substring(0, cut).trim()
            buf.delete(0, cut)
            if (sentence.isNotBlank()) onSentence(sentence)
            cut = boundary()
        }
    }

    /** Emit whatever's left (call at end of stream). */
    fun flush() {
        val rest = buf.toString().trim()
        buf.setLength(0)
        if (rest.isNotBlank()) onSentence(rest)
    }

    /** Index just past the first sentence terminator, or -1 if none yet. */
    private fun boundary(): Int {
        for (i in buf.indices) {
            val c = buf[i]
            if (c == '\n' || ((c == '.' || c == '!' || c == '?' || c == ':') && i + 1 >= minLen)) {
                // require the terminator to be followed by space/end (not mid-number/abbrev like "3.14")
                if (c == '\n' || i + 1 >= buf.length || buf[i + 1] == ' ') return i + 1
            }
        }
        return -1
    }
}
