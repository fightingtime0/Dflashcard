package com.dflashcard.app

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * Picks the voice for a piece of card text from the script it is written in.
 *
 * Returns null when the script says nothing useful, and the caller then hides the speak button
 * rather than reading, say, a German word aloud in an Indonesian voice.
 *
 * ponytail: script detection covers the CJK decks this app was built around. A Latin-script deck
 * needs a real language field on Deck — add one when a non-CJK deck actually shows up.
 */
fun localeFor(text: String): Locale? {
    var hasHan = false
    for (ch in text) {
        when (ch.code) {
            // Hiragana and katakana are unambiguous, so they win immediately.
            in 0x3040..0x30FF -> return Locale.JAPANESE
            in 0x1100..0x11FF, in 0xA960..0xA97F, in 0xAC00..0xD7FF -> return Locale.KOREAN
            in 0x4E00..0x9FFF, in 0x3400..0x4DBF -> hasHan = true
        }
    }
    // Han on its own could be Chinese, but in this app's decks it is Japanese.
    return if (hasHan) Locale.JAPANESE else null
}

/**
 * Japanese entries are written `水（みず）`, where the bracketed part is the reading. Speaking the
 * reading alone is both correct and avoids the engine saying the word twice.
 */
fun speakableText(text: String): String {
    val open = text.indexOf('（')
    val close = text.indexOf('）', startIndex = open + 1)
    val reading = if (open >= 0 && close > open) text.substring(open + 1, close).trim() else ""
    return reading.ifEmpty { text }
}

class Speaker(context: Context) {

    @Volatile
    private var ready = false

    private val tts = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
    }

    /** Silently does nothing when the engine is still starting or has no voice installed. */
    fun speak(text: String) {
        if (!ready) return
        val locale = localeFor(text) ?: return
        if (tts.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) return
        tts.speak(speakableText(text), TextToSpeech.QUEUE_FLUSH, null, "card")
    }

    fun shutdown() {
        ready = false
        tts.shutdown()
    }
}

@Composable
fun rememberSpeaker(): Speaker {
    val context = LocalContext.current
    val speaker = remember(context) { Speaker(context) }
    DisposableEffect(speaker) { onDispose { speaker.shutdown() } }
    return speaker
}
