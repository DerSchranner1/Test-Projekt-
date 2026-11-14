package de.schranner.tastatur

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.os.Bundle
import android.os.Vibrator
import android.speech.SpeechRecognizer
import android.text.style.SuggestionSpan
import android.view.textservice.SuggestionsInfo
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer
import org.robolectric.util.ReflectionHelpers

/** Emojis, Rechtschreibprüfung, Vibration und Diktieren ohne Schlüssel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w384dp-h832dp-450dpi")
class FeaturesTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    // ---------------------------------------------------------------- Emojis

    @Test fun allEmojisAreImportedWithSearchAndVariants() {
        EmojiData.ensureLoaded(context)
        val total = EmojiData.categories.sumOf { it.emojis.size }
        println("Emojis: $total in ${EmojiData.categories.size} Gruppen, animiert: ${EmojiData.animated().size}")
        assertTrue(total > 1500)
        assertEquals(9, EmojiData.categories.size)
        assertTrue(EmojiData.animated().size > 500)
        assertTrue(EmojiData.search("pizza").any { it.text == "🍕" })
        assertTrue(EmojiData.search("hund").any { it.text == "🐕" || it.text == "🐶" })
        assertTrue(EmojiData.search("dog").isNotEmpty())
        assertEquals("🍕", EmojiData.suggestionFor("Pizza"))
        assertEquals("🙏", EmojiData.suggestionFor("danke"))
        // Hauttöne
        val wave = EmojiData.categories.flatMap { it.emojis }.first { it.text == "👋" }
        assertTrue(wave.alternates.contains("👋🏽"))
    }

    // ---------------------------------------------------------------- Rechtschreibprüfung

    @Test fun spellCheckerFlagsTyposAndSuggestsFixes() {
        val engine = EngineHolder.get(context)
        while (!engine.isFullyLoaded()) Thread.sleep(20)
        val typo = KlarSpellChecker.check(engine, "fehelr", 5)
        assertTrue(typo.suggestionsAttributes and SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO != 0)
        val fixes = (0 until typo.suggestionsCount).map { typo.getSuggestionAt(it) }
        println("Rechtschreibprüfung fehelr -> $fixes")
        assertTrue(fixes.contains("Fehler"))
        for (ok in listOf("Haus", "Haustür", "the", "WhatsApp", "USA", "ok", "E-Mail", "x")) {
            val r = KlarSpellChecker.check(engine, ok, 5)
            assertTrue(ok, r.suggestionsAttributes and SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY != 0)
        }
    }

    @Test fun typedTyposGetTheSystemMisspelledMark() {
        val h = KeyboardHarness()
        h.type("ein fehelr ")
        val spans = h.edit.text.getSpans(0, h.edit.text.length, SuggestionSpan::class.java)
        assertEquals(1, spans.size)
        // Nur „falsch geschrieben“ – so zeichnet Android die rote Unterstreichung
        assertEquals(SuggestionSpan.FLAG_MISSPELLED, spans[0].flags)
    }

    // ---------------------------------------------------------------- Vibration

    @Test fun vibrationStrengthIsConfigurable() {
        val vib = shadowOf(context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
        val strong = KeyboardHarness { it.vibration = 100 }
        strong.type("a")
        assertTrue(vib.isVibrating)
        assertEquals(30L, vib.milliseconds)
        val off = KeyboardHarness { it.vibration = 0 }
        vib.milliseconds.let { }  // Zustand vom ersten Tippen
        ReflectionHelpers.setField(vib, "vibrating", false)
        off.type("a")
        assertFalse(vib.isVibrating)
    }

    // ---------------------------------------------------------------- Diktieren ohne Schlüssel

    private fun voiceHarness(): KeyboardHarness {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val cn = ComponentName("com.example.speech", "com.example.speech.Service")
        shadowOf(app.packageManager).addServiceIfNotPresent(cn)
        shadowOf(app.packageManager).addIntentFilterForService(cn, IntentFilter("android.speech.RecognitionService"))
        return KeyboardHarness()
    }

    private fun results(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }

    @Test fun dictationShowsLiveTextAndPunctuation() {
        val h = voiceHarness()
        h.call("onMicTap")
        val sr = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        sr.triggerOnReadyForSpeech(Bundle())
        h.settle()
        assertEquals("voiceRow", h.visibleRow())
        sr.triggerOnPartialResults(results("hallo wie"))
        h.settle()
        assertEquals("Hallo wie", h.text)            // erscheint schon beim Sprechen
        sr.triggerOnPartialResults(results("hallo wie geht es dir"))
        h.settle()
        assertEquals("Hallo wie geht es dir", h.text)
        sr.triggerOnResults(results("hallo wie geht es dir fragezeichen"))
        h.settle()
        assertEquals("Hallo wie geht es dir? ", h.text)
        assertFalse(h.visibleRow() == "voiceRow")
        // Danach normal weitertippen
        h.type("gut")
        assertEquals("Hallo wie geht es dir? Gut", h.text)
    }

    @Test fun dictationSegmentsContinue() {
        val h = voiceHarness()
        h.type("ok ")
        h.call("onMicTap")
        val sr = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        sr.triggerOnReadyForSpeech(Bundle())
        h.service.onSegment("Ich komme gleich.")
        h.settle()
        assertEquals("Ok ich komme gleich. ", h.text)
        assertEquals("voiceRow", h.visibleRow())      // hört weiter zu
        h.service.onSegment("Bis später!")
        h.settle()
        assertEquals("Ok ich komme gleich. Bis später! ", h.text)
    }

    @Test fun typingWhileDictatingKeepsTheSpokenText() {
        val h = voiceHarness()
        h.call("onMicTap")
        val sr = shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
        sr.triggerOnReadyForSpeech(Bundle())
        sr.triggerOnPartialResults(results("guten morgen"))
        h.settle()
        h.space()
        h.type("x")
        assertEquals("Guten morgen x", h.text)
    }
}
