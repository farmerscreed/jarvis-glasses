package com.echo.app.translate

/**
 * Pure text checks for the translator's echo control (no Android, unit-tested in
 * `src/test/.../TranslateTextTest.kt` with real transcripts).
 *
 * Why they exist (P0, 2026-10-11): Gemini Live Translate sometimes labels the wearer's English
 * (Nigerian accent) as another language, e.g. Swahili. Then `echoTargetLanguage:false` doesn't
 * silence it — it "translates" English → English and plays it in his ear, the phone mic hears that
 * playback, and it repeats. We don't rely on Gemini's language code for English any more.
 */
object TranslateText {

    /**
     * Common English words that are NOT also everyday words in French, Spanish, Portuguese, Italian,
     * German, Dutch or Swahili (so "a", "me", "do", "in", "on", "so", "was", "also", "die" are left out).
     */
    private val ENGLISH = (
        "the i you to is it and of that what don't i'm it's that's let's can't won't didn't isn't " +
            "what's where's who's how's there's you're we're they're i've i'll you'll name " +
            "my your we they he she this these those are were have has had will would should could " +
            "with for not can how why when where who which there their them our us him her his " +
            "be been being just know need want talk tell say said stop please thank thanks yes yeah " +
            "okay ok hello hi let about from but or if because very really good well right now here " +
            "going go get got make made think see look come like only some any all one two " +
            "translating translate translation listen listening jarvis reply speak"
        ).split(' ').filter { it.isNotBlank() }.toHashSet()

    /** Latin-alphabet word tokens, lower-case, apostrophes kept ("don't"). */
    fun latinWords(t: String): List<String> =
        Regex("[\\p{IsLatin}]+(?:['’][\\p{IsLatin}]+)?").findAll(t.lowercase())
            .map { it.value.replace('’', '\'') }.toList()

    /**
     * True when Latin-script text reads as English. Short fragments ("Stop. Stop.", "Let me talk.")
     * must be nearly all English words; longer ones need a solid share of English function words.
     */
    fun looksEnglish(text: String): Boolean {
        val w = latinWords(text)
        if (w.isEmpty()) return false
        val hits = w.count { it in ENGLISH }
        return when {
            w.size <= 2 -> hits == w.size
            w.size == 3 -> hits >= 2
            else -> hits >= 2 && hits.toDouble() / w.size >= 0.34
        }
    }

    /**
     * Words for echo comparison. Chinese/Japanese/Korean have no spaces, so they're compared as
     * character PAIRS — single characters (你, 好, 我…) are shared by unrelated sentences, which
     * wrongly dropped the video's "你好吗?我" as an echo of "你还好吗？" (10-11).
     */
    fun wordsOf(t: String): List<String> {
        val clean = t.lowercase().replace(Regex("[\\p{Punct}¿¡。、，！？：；“”‘’«»]"), " ").trim()
        if (clean.isEmpty()) return emptyList()
        if (clean.none { Character.UnicodeScript.of(it.code) in CJK }) return clean.split(Regex("\\s+")).filter { it.isNotBlank() }
        val chars = clean.filter { !it.isWhitespace() }
        return if (chars.length < 2) listOf(chars) else chars.windowed(2)
    }

    /**
     * Heard text is our own recent playback coming back through the mic: ≥3 words and ≥80 % of
     * them appear in what was played. (Real echoes are near-verbatim; 60 % wrongly dropped Law's
     * "Can you hear me now?" after "Just now, did you hear?" was played — 10-11.)
     */
    fun isEcho(heard: String, played: Collection<String>): Boolean {
        val words = wordsOf(heard)
        if (words.size < 3 || played.isEmpty()) return false
        val playedWords = played.flatMap { wordsOf(it) }.toHashSet()
        if (playedWords.isEmpty()) return false
        val hit = words.count { it in playedWords }
        return hit >= 3 && hit.toDouble() / words.size >= 0.8
    }

    /** Loudest sample in a PCM16 little-endian chunk (0..32767). */
    fun peakLevel(pcm: ByteArray): Int {
        var peak = 0
        var i = 0
        while (i + 1 < pcm.size) {
            val s = (pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)
            val a = if (s < 0) -s else s
            if (a > peak) peak = a
            i += 2
        }
        return minOf(peak, 32767)
    }

    /**
     * Short commands ("stop", "let me talk") are below [isEcho]'s 3-word floor; this catches a
     * fragment whose words ALL appear in very recent playback, so a played translation can never
     * trigger a voice command.
     */
    fun allWordsPlayed(heard: String, played: Collection<String>): Boolean {
        val words = wordsOf(heard)
        if (words.isEmpty() || played.isEmpty()) return false
        val playedWords = played.flatMap { wordsOf(it) }.toHashSet()
        return words.all { it in playedWords }
    }

    /**
     * The "translation" is (nearly) the same words as the input → the input was already in the
     * target language (English mislabelled as Swahili, say). Decided once the output has ≥2 words,
     * or on whatever there is when [final]. Returns null while it's too early to tell.
     */
    fun isIdentity(input: String, output: String, final: Boolean = false): Boolean? {
        val out = wordsOf(output)
        if (out.isEmpty() || (out.size < 2 && !final)) return null
        val inp = wordsOf(input).toHashSet()
        if (inp.isEmpty()) return null
        val hit = out.count { it in inp }
        return hit.toDouble() / out.size >= 0.7
    }

    private val CJK = setOf(
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL,
    )
}
