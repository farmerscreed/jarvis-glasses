package com.echo.app.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Echo-control checks against real transcripts. The P0 evidence (saved listen session,
 * 2026-10-11 08:57 UTC) — Law's English labelled Swahili and played back to him:
 *   [them · sw] Let me talk.  →  Let me talk.  (×3)
 *   [them · sw] Stop. Stop.   →  Stop. Stop.   (×2)
 *   [them · sw] Mimi sihitaji kuhusu nutrition.  →  I don't need to talk about nutrition.
 *   [them · sw] I don't need to talk about nutrition.  →  I don't need to talk about nutrition. (×3)
 */
class TranslateTextTest {

    // ── looksEnglish ──────────────────────────────────────────────────────────────────────────
    @Test fun lawsEnglishLooksEnglish() {
        assertTrue(TranslateText.looksEnglish("Let me talk."))
        assertTrue(TranslateText.looksEnglish("Stop. Stop."))
        assertTrue(TranslateText.looksEnglish("I don't need to talk about nutrition."))
        assertTrue(TranslateText.looksEnglish("Jarvis, stop translating"))
        assertTrue(TranslateText.looksEnglish("How are you doing?"))
        assertTrue(TranslateText.looksEnglish("Thank you"))
        assertTrue(TranslateText.looksEnglish("What's your name?"))
    }

    @Test fun otherLanguagesDoNotLookEnglish() {
        assertFalse(TranslateText.looksEnglish("Mimi sihitaji kuhusu nutrition."))
        assertFalse(TranslateText.looksEnglish("Je ne sais pas ce que tu veux dire."))
        assertFalse(TranslateText.looksEnglish("Bonjour"))
        assertFalse(TranslateText.looksEnglish("Okay, merci beaucoup"))
        assertFalse(TranslateText.looksEnglish("Yo no sé lo que quieres decir."))
        assertFalse(TranslateText.looksEnglish("Eu não sei o que você quer."))
        assertFalse(TranslateText.looksEnglish("Also ich will nach Hause gehen."))
        assertFalse(TranslateText.looksEnglish("Ich weiß nicht, was du willst."))
        assertFalse(TranslateText.looksEnglish("Habari yako, rafiki yangu?"))
        assertFalse(TranslateText.looksEnglish("你好，你在做什么？"))
        assertFalse(TranslateText.looksEnglish(""))
    }

    // ── effectiveLang ─────────────────────────────────────────────────────────────────────────
    @Test fun englishLabelledSwahiliBecomesEnglish() {
        assertEquals("en", TranslationController.effectiveLang("sw", "Let me talk."))
        assertEquals("en", TranslationController.effectiveLang("sw", "Stop. Stop."))
        assertEquals("en", TranslationController.effectiveLang("sw", "I don't need to talk about nutrition."))
    }

    @Test fun realSwahiliStaysSwahili() {
        assertEquals("sw", TranslationController.effectiveLang("sw", "Mimi sihitaji kuhusu nutrition."))
        assertEquals("fr", TranslationController.effectiveLang("fr", "Je ne sais pas."))
    }

    @Test fun shortFragmentJudgedWithItsLine() {
        // Fragments arrive in pieces: "nutrition." alone isn't English, but it ends an English line.
        assertEquals("en", TranslationController.effectiveLang("sw", " nutrition.", lineSoFar = "I don't need to talk about"))
        assertEquals("sw", TranslationController.effectiveLang("sw", " nutrition.", lineSoFar = "Mimi sihitaji kuhusu"))
        assertEquals("fr", TranslationController.effectiveLang("fr", " Okay.", lineSoFar = "Je ne sais pas ce que"))
    }

    @Test fun scriptStillWins() {
        assertEquals("zh-Hans", TranslationController.effectiveLang("fr", "你好，你在做什么？"))
        assertEquals("ar", TranslationController.effectiveLang("en", "مرحبا كيف حالك"))
    }

    // ── identity guard ────────────────────────────────────────────────────────────────────────
    @Test fun sameWordsBackIsIdentity() {
        assertEquals(true, TranslateText.isIdentity("I don't need to talk about nutrition.", "I don't need to talk about nutrition."))
        assertEquals(true, TranslateText.isIdentity("Let me talk.", "Let me talk."))
        assertEquals(true, TranslateText.isIdentity("Stop. Stop.", "Stop. Stop."))
    }

    @Test fun realTranslationIsNotIdentity() {
        assertEquals(false, TranslateText.isIdentity("Mimi sihitaji kuhusu nutrition.", "I don't need to talk about nutrition."))
        assertEquals(false, TranslateText.isIdentity("Le restaurant est fermé.", "The restaurant is closed."))
        assertEquals(false, TranslateText.isIdentity("你好，你在做什么？", "Hello, what are you doing?"))
    }

    @Test fun identityWaitsForTwoWordsUnlessFinal() {
        assertNull(TranslateText.isIdentity("Let me talk.", "Let"))
        assertEquals(true, TranslateText.isIdentity("Stop.", "Stop.", final = true))
        assertEquals(false, TranslateText.isIdentity("Oui.", "Yes.", final = true))
    }

    // ── echo filter ───────────────────────────────────────────────────────────────────────────
    @Test fun ownEarPlaybackHeardAgainIsEcho() {
        val played = listOf("I don't need to talk about nutrition.")
        assertTrue(TranslateText.isEcho("I don't need to talk about nutrition.", played))
        assertTrue(TranslateText.isEcho("need to talk about nutrition", played))
    }

    @Test fun newSpeechIsNotEcho() {
        val played = listOf("I don't need to talk about nutrition.")
        assertFalse(TranslateText.isEcho("Mimi sihitaji kuhusu nutrition.", played))
        assertFalse(TranslateText.isEcho("Where is the market?", played))
        assertFalse(TranslateText.isEcho("Let me talk.", emptyList()))
    }

    @Test fun overlappingWordsAreNotAnEcho() {
        // 10-11 11:22:40 — Law's own question after JARVIS played "Just now, did you hear?"
        assertFalse(TranslateText.isEcho("Can you hear me now?", listOf(" Just", " now, did you hear?")))
    }

    @Test fun peakLevelOfPcm() {
        assertEquals(0, TranslateText.peakLevel(ByteArray(3200)))
        val loud = ByteArray(4).also { it[0] = 0x10; it[1] = 0x27; it[2] = 0xF0.toByte(); it[3] = 0xD8.toByte() } // 10000, -10000
        assertEquals(10000, TranslateText.peakLevel(loud))
    }

    @Test fun chineseSharingCharactersIsNotAnEcho() {
        // 10-11 11:42:13 — the video's line vs Law's translation played on the loudspeaker.
        assertFalse(TranslateText.isEcho("你好吗?我", listOf("你还好吗？", "一切都好吗？", "现在，我们")))
    }

    @Test fun commandsSurviveMishearing() {
        assertEquals("talk", TranslationController.commandOf("Jarpis, let me talk.", TranslateMode.LISTEN))
        assertEquals("talk", TranslationController.commandOf(" Let me talk.", TranslateMode.LISTEN))
        assertEquals("stop", TranslationController.commandOf("Jervis stop", TranslateMode.CONVERSATION))
        assertEquals("stop", TranslationController.commandOf("Just stop translation.", TranslateMode.LISTEN))
        assertEquals("listen", TranslationController.commandOf("Jarvis, just listen", TranslateMode.CONVERSATION))
        assertNull(TranslationController.commandOf("Let me talk", TranslateMode.CONVERSATION))
        assertNull(TranslationController.commandOf("My name is Ai Lin.", TranslateMode.LISTEN))
    }

    @Test fun chineseEchoComparedByCharacter() {
        assertTrue(TranslateText.isEcho("你在做什么", listOf("你好，你在做什么？")))
    }

    // ── commands vs our own playback ──────────────────────────────────────────────────────────
    @Test fun playedCommandWordsAreOurs() {
        assertTrue(TranslateText.allWordsPlayed("Stop translating.", listOf("Please stop translating now.")))
        assertFalse(TranslateText.allWordsPlayed("Jarvis, stop translating", listOf("Please stop translating now.")))
        assertFalse(TranslateText.allWordsPlayed("Stop translating.", emptyList()))
    }
}
