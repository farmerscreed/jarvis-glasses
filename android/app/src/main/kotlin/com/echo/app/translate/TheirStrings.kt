package com.echo.app.translate

import java.util.Locale

/**
 * The few words the OTHER person reads on the face-to-face panel, in their own language — they can't
 * be expected to read English instructions. Fixed, hand-checked phrases (no network needed).
 */
object TheirStrings {
    /** Shown before you've said anything: invites them to speak. */
    private val greeting = mapOf(
        "zh-Hans" to "您好！请说话，我会为您翻译。",
        "zh-Hant" to "您好！請說話，我會為您翻譯。",
        "fr" to "Bonjour ! Parlez, je traduis.",
        "ar" to "مرحبًا! تفضّل بالكلام، وسأترجم لك.",
        "es" to "¡Hola! Hable, yo traduzco.",
        "pt" to "Olá! Fale, eu traduzo.",
        "de" to "Hallo! Bitte sprechen Sie – ich übersetze.",
        "it" to "Salve! Parli pure, traduco io.",
        "ja" to "こんにちは！話してください。翻訳します。",
        "ko" to "안녕하세요! 말씀하세요. 통역해 드릴게요.",
        "hi" to "नमस्ते! बोलिए, मैं अनुवाद करूँगा।",
        "ru" to "Здравствуйте! Говорите, я переведу.",
        "tr" to "Merhaba! Konuşun, ben çevireceğim.",
        "sw" to "Habari! Ongea, nitatafsiri.",
        "ha" to "Sannu! Yi magana, zan fassara.",
    )

    /** Before their language is known: one line in several languages. */
    const val MULTI_GREETING = "Hello · 您好 · Bonjour · مرحبًا · Hola\nPlease speak — I will translate."

    fun greeting(lang: String): String = greeting[TranslationController.normalizeLang(lang)] ?: MULTI_GREETING

    /** The language's own name for itself ("中文", "français", "العربية"). */
    fun nativeName(lang: String): String {
        val code = TranslationController.normalizeLang(lang)
        if (code.isBlank()) return "…"
        val loc = Locale.forLanguageTag(code)
        return loc.getDisplayLanguage(loc).replaceFirstChar { it.titlecase(loc) }.ifBlank { code }
    }
}
