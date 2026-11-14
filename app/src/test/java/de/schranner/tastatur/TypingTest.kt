package de.schranner.tastatur

import android.text.InputType
import android.text.style.SuggestionSpan
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Bedient die echte Tastatur (Tasten antippen) und prüft, was im Textfeld ankommt. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w384dp-h832dp-450dpi")
class TypingTest {

    @Test fun typesWordsWithAutoCapitalization() {
        val h = KeyboardHarness()
        h.type("hallo wie geht es dir")
        assertEquals("Hallo wie geht es dir", h.text)
        h.type(". ")
        h.type("gut")
        assertEquals("Hallo wie geht es dir. Gut", h.text)
    }

    @Test fun doubleSpaceMakesPeriod() {
        val h = KeyboardHarness()
        h.type("ja")
        h.space(); h.space()
        assertEquals("Ja. ", h.text)
        h.type("ok")
        assertEquals("Ja. Ok", h.text)
    }

    @Test fun backspaceDeletesAndRecomposes() {
        val h = KeyboardHarness()
        h.type("hallo du")
        h.backspace()
        assertEquals("Hallo d", h.text)
        h.backspace(); h.backspace()
        assertEquals("Hallo", h.text)
        h.backspace()
        assertEquals("Hall", h.text)
        h.type("e")
        assertEquals("Halle", h.text)
    }

    @Test fun emptyFieldShowsToolbarThenSuggestions() {
        val h = KeyboardHarness()
        assertEquals("toolRow", h.visibleRow())
        h.type("h")
        assertEquals("suggRow", h.visibleRow())
    }

    @Test fun nextWordPredictionsAndPicking() {
        val h = KeyboardHarness()
        h.type("vielen ")
        println("Nach 'Vielen ': " + h.suggestions())
        assertTrue(h.suggestions().contains("Dank"))
        h.pick("Dank")
        assertEquals("Vielen Dank ", h.text)
        println("Nach 'Vielen Dank ': " + h.suggestions())
        h.type("f")
        println("Nach 'f': " + h.suggestions())
        assertTrue(h.suggestions().contains("für"))
        h.pick("für")
        assertEquals("Vielen Dank für ", h.text)
    }

    @Test fun completionWhileTyping() {
        val h = KeyboardHarness()
        h.type("guten mor")
        println("guten mor: " + h.suggestions())
        assertTrue(h.suggestions().contains("Morgen"))
        h.pick("Morgen")
        assertEquals("Guten Morgen ", h.text)
    }

    @Test fun unknownWordIsMarkedNotChanged() {
        val h = KeyboardHarness()
        h.type("das ist ein fehelr ")
        assertEquals("Das ist ein fehelr ", h.text)
        val spans = h.edit.text.getSpans(0, h.edit.text.length, SuggestionSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals("fehelr", h.text.substring(h.edit.text.getSpanStart(spans[0]), h.edit.text.getSpanEnd(spans[0])))
        println("Nach Tippfehler: " + h.suggestions())
        assertTrue(h.suggestions().contains("Fehler"))
        h.pick("Fehler")
        assertEquals("Das ist ein Fehler ", h.text)
    }

    @Test fun autocorrectFixesAndBackspaceUndoes() {
        val h = KeyboardHarness { it.autocorrect = true }
        h.type("das ist ein fehelr ")
        assertEquals("Das ist ein Fehler ", h.text)
        h.backspace()
        assertEquals("Das ist ein fehelr", h.text)
    }

    @Test fun englishIsDetected() {
        val h = KeyboardHarness()
        h.type("thank ")
        println("thank: " + h.suggestions())
        assertTrue(h.suggestions().contains("you"))
        h.pick("you")
        h.type("for the ")
        println("thank you for the: " + h.suggestions())
        h.type("i ")
        assertEquals("Thank you for the i ", h.text)
        // und wieder Deutsch
        val g = KeyboardHarness()
        g.type("ich habe ")
        println("ich habe: " + g.suggestions())
        assertTrue(g.suggestions().none { it in listOf("the", "you", "and") })
    }

    @Test fun symbolsAndNumbers() {
        val h = KeyboardHarness()
        h.type("um 5")
        h.tap(KeyType.SYMBOLS)
        h.tap(":")
        h.tap("3"); h.tap("0")
        assertEquals("Um 5:30", h.text)
        h.space()   // zurück zu den Buchstaben
        h.type("uhr")
        assertEquals("Um 5:30 uhr", h.text)
    }

    @Test fun touchAssistPrefersLikelyLetter() {
        // „Vielen Dan“ + Tipp genau auf die Grenze zwischen J und K (leicht auf J-Seite) -> K
        val on = KeyboardHarness { it.touchAssist = 100 }
        on.type("vielen dan")
        val k = on.keyboard.rows.flatten().first { it.label == "k" }
        on.tapKey(k, dx = -0.62f)
        assertEquals("Vielen dank", on.text)
        val off = KeyboardHarness { it.touchAssist = 0 }
        off.type("vielen dan")
        val k2 = off.keyboard.rows.flatten().first { it.label == "k" }
        off.tapKey(k2, dx = -0.62f)
        assertEquals("Vielen danj", off.text)
        // Die Mitte der Taste gewinnt immer
        val j = on.keyboard.rows.flatten().first { it.label == "j" }
        on.backspace()
        on.tapKey(j)
        assertEquals("Vielen danj", on.text)
    }

    @Test fun layoutsAndEszettKey() {
        val h = KeyboardHarness { it.layout = "qwertz_umlaut"; it.eszettKey = true }
        h.type("größe über")
        assertEquals("Größe über", h.text)
        val q = KeyboardHarness { it.layout = "qwerty"; it.eszettKey = false }
        val row1 = q.keyboard.rows[1].joinToString("") { it.label }
        assertEquals("qwertyuiop", row1)
    }

    @Test fun passwordFieldHasNoSuggestions() {
        val h = KeyboardHarness(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        h.type("geheim")
        assertEquals("geheim", h.text)
        assertTrue(h.suggestions().all { it.isEmpty() })
    }

    @Test fun editPanelSelectsAndCopies() {
        val h = KeyboardHarness()
        h.type("hallo welt")
        val panel = ReflectionHelpers.callInstanceMethod<View>(h.service, "buildEditPanel")
        assertTrue(panel != null)
        ReflectionHelpers.callInstanceMethod<Any>(
            h.service, "menuAction",
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, android.R.id.selectAll),
            ReflectionHelpers.ClassParameter.from(String::class.java, null),
        )
        assertEquals(0, h.edit.selectionStart)
        assertEquals(10, h.edit.selectionEnd)
    }

    @Test fun resizeChangesKeyboardHeight() {
        val h = KeyboardHarness()
        val before = h.keyboard.height
        h.call("startResize")
        assertEquals("resizeRow", h.visibleRow())
        ReflectionHelpers.callInstanceMethod<Any>(
            h.service, "setKeyboardSize", ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 130),
        )
        h.settle()
        assertTrue(h.keyboard.height > before * 1.2)
        assertEquals(130, h.prefs.keyHeightPercent)
        h.call("endResize")
        assertFalse(h.visibleRow() == "resizeRow")
    }
}
