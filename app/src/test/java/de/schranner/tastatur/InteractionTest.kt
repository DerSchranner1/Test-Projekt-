package de.schranner.tastatur

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Gesten und Bedienung: langes Drücken, Wischen, Ziehen, Bereiche – alles über echte Berührungen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w384dp-h832dp-450dpi")
class InteractionTest {

    private fun altItems(h: KeyboardHarness): List<String> {
        val view = ReflectionHelpers.getField<Any>(h.keyboard, "altsView")
        return ReflectionHelpers.getField(view, "items")
    }

    @Test fun longPressGivesUmlautDirectly() {
        val h = KeyboardHarness()
        h.type("x")
        h.longPress(h.letter('a')); assertEquals("Xä", h.text)
        h.longPress(h.letter('o')); assertEquals("Xäö", h.text)      // Taste am rechten Rand
        h.longPress(h.letter('u')); assertEquals("Xäöü", h.text)
        h.longPress(h.letter('s')); assertEquals("Xäöüß", h.text)
        h.longPress(h.letter('i')); assertEquals("Xäöüßí", h.text)
        h.longPress(h.letter('e')); assertEquals("Xäöüßíé", h.text)
    }

    @Test fun longPressPopupDoesNotContainTheKeyItself() {
        val h = KeyboardHarness()
        h.type("x")
        for (c in "aouseicn") {
            val k = h.letter(c)
            h.longPress(k, release = false)
            val items = altItems(h)
            assertTrue("$c: $items", items.isNotEmpty())
            assertFalse("$c steht in $items", items.contains(c.toString()))
            h.release(k)
        }
    }

    @Test fun longPressSlideSelectsOtherVariantAndSlidingAwayCancels() {
        val h = KeyboardHarness()
        h.type("x")
        h.longPress(h.letter('a'), slideCells = 1f)
        assertEquals("Xá", h.text)
        h.longPress(h.letter('a'), slideDown = 3f)
        assertEquals("Xá", h.text)                                    // weit weggeschoben: nichts eingefügt
        h.longPress(h.letter('o'), slideCells = -1f)                  // am rechten Rand wächst die Reihe nach links
        assertEquals("Xáó", h.text)
    }

    @Test fun onUmlautLayoutLongPressSkipsLettersWithOwnKey() {
        val h = KeyboardHarness { it.layout = "qwertz_umlaut"; it.eszettKey = true }
        h.type("x")
        h.longPress(h.letter('a'), release = false)
        assertFalse(altItems(h).contains("ä"))
        h.release(h.letter('a'))
        assertEquals("Xá", h.text)
        h.longPress(h.letter('s'), release = false)
        assertFalse(altItems(h).contains("ß"))
        h.release(h.letter('s'))
        // Tasten ohne Sonderzeichen tippen bei langem Druck einfach den Buchstaben
        h.longPress(h.letter('ä'))
        assertEquals("Xáśä", h.text)
    }

    @Test fun longPressOnPlainKeyTypesIt() {
        val h = KeyboardHarness()
        h.longPress(h.letter('k'))
        assertEquals("K", h.text)
    }

    @Test fun swipeTypingWritesWords() {
        val h = KeyboardHarness()
        h.swipe("hallo")
        assertEquals("Hallo", h.text)
        h.swipe("wie")
        assertEquals("Hallo wie", h.text)
        h.swipe("geht")
        h.type(" ")
        assertEquals("Hallo wie geht ", h.text)
        h.swipe("morgen")
        h.backspace()                                                 // löscht das ganze gewischte Wort
        assertEquals("Hallo wie geht ", h.text)
    }

    @Test fun fastSwipeWithFewSamplesStillEndsOnTheLastKey() {
        // Sehr schneller Wischer: nur Aufsetzen, ein Zwischenpunkt, Abheben (so auf dem Emulator beobachtet)
        val h = KeyboardHarness()
        h.type("und ")
        val d = h.letter('d').bounds
        val u = h.letter('u').bounds
        val t = android.os.SystemClock.uptimeMillis()
        fun ev(a: Int, x: Float, y: Float) = h.keyboard.dispatchTouchEvent(MotionEvent.obtain(t, t, a, x, y, 0))
        ev(MotionEvent.ACTION_DOWN, d.centerX(), d.centerY())
        ev(MotionEvent.ACTION_MOVE, d.centerX() + (u.centerX() - d.centerX()) * 0.3f, d.centerY() + (u.centerY() - d.centerY()) * 0.3f)
        ev(MotionEvent.ACTION_UP, u.centerX(), u.centerY())
        h.settle()
        assertEquals("Und du", h.text)
    }

    @Test fun shortJunkWordsAreNotSuggested() {
        val h = KeyboardHarness()
        h.type("d")
        println("Vorschläge für d: " + h.suggestions())
        assertTrue(h.suggestions().none { it in listOf("dt", "df", "dg") })
    }

    @Test fun longPressOnSpaceTurnsKeyboardIntoTrackpad() {
        val h = KeyboardHarness()
        h.type("hallo welt")
        val moves = ArrayList<String>()
        val real = h.service
        h.keyboard.listener = object : KeyboardView.Listener by real {
            override fun onCursorMove(steps: Int) { moves += "x$steps" }
            override fun onCursorMoveVertical(steps: Int) { moves += "y$steps" }
        }
        val space = h.keyOf(KeyType.SPACE)
        // lang drücken, dann nach links und nach oben schieben
        h.longPress(space, release = false)
        val t = android.os.SystemClock.uptimeMillis()
        val x = space.bounds.centerX()
        val y = space.bounds.centerY()
        h.keyboard.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_MOVE, x - 120f, y, 0))
        h.keyboard.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_MOVE, x - 120f, y - 160f, 0))
        h.keyboard.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_UP, x - 120f, y - 160f, 0))
        h.settle()
        println("Tastfeld-Bewegungen: $moves")
        assertTrue(moves.any { it.startsWith("x-") })
        assertTrue(moves.any { it.startsWith("y-") })
        assertEquals("Hallo welt", h.text)           // kein Leerzeichen getippt
    }

    @Test fun unknownWordIsSquiggledInTheStripAndGrammarHintWorks() {
        val h = KeyboardHarness()
        h.type("das unterkringeln ")
        val slots: Array<android.widget.TextView?> = h.field("slots")
        val marked = slots.filterIsInstance<SquiggleTextView>().firstOrNull { it.squiggle }
        assertTrue("unbekanntes Wort soll unterkringelt sein", marked != null && marked.text.toString() == "unterkringeln")
        h.type("funktioniert ")
        println("Grammatik-Hinweis: " + h.suggestions())
        assertTrue(h.suggestions().contains("Unterkringeln"))
        h.pick("Unterkringeln")
        assertEquals("Das Unterkringeln funktioniert ", h.text)
        // Nach „beim“ gleich groß
        h.type("beim lauf")
        assertTrue(h.suggestions().contains("Laufen"))
    }

    @Test fun swipeOnBackspaceDeletesWords() {
        val h = KeyboardHarness()
        h.type("eins zwei drei")
        h.drag(h.keyOf(KeyType.DELETE), -160f)
        assertEquals("Eins zwei ", h.text)
    }

    @Test fun doubleTapShiftLocksCapitals() {
        val h = KeyboardHarness()
        h.type("a ")
        h.tap(KeyType.SHIFT); h.tap(KeyType.SHIFT)
        h.type("abc")
        assertEquals("A ABC", h.text)
        h.tap(KeyType.SHIFT)
        h.type("d")
        assertEquals("A ABCd", h.text)
    }

    @Test fun emojiKeyOpensPanelAndInsertsEmoji() {
        val h = KeyboardHarness()
        h.type("hi ")
        h.tap(KeyType.EMOJI)
        val media = h.field<MediaPanel>("mediaPanel")
        assertEquals(View.VISIBLE, media.visibility)
        h.layout()
        val first = media.tapFirstEmoji()
        h.settle()
        assertTrue("Emoji fehlt: ${h.text}", first != null && h.text == "Hi $first")
        // Wieder öffnen: zuletzt verwendete stehen oben, darunter alle Gruppen (nichts wirkt „leer“)
        h.tap(KeyType.EMOJI)
        h.layout()
        val rows = ReflectionHelpers.getField<List<Any>>(media, "emojiRows")
        assertTrue("nur ${rows.size} Zeilen", rows.size > 150)
        // Suche
        media.search(MediaPanel.Tab.EMOJI, "pizza")
        h.settle()
        media.tapFirstEmoji()
        h.settle()
        assertTrue(h.text.endsWith("🍕"))
    }

    @Test fun emojiSearchWorksFromTheSearchButton() {
        val h = KeyboardHarness()
        h.type("hi ")
        h.tap(KeyType.EMOJI)
        h.layout()
        // Lupe im Emoji-Bereich antippen: die Tastatur erscheint mit Suchzeile
        assertTrue("Lupe nicht gefunden", h.clickAll { it is android.widget.TextView && it.text.toString() == "🔍" })
        assertEquals("searchRow", h.visibleRow())
        h.type("pizza")
        h.layout()
        assertEquals("Hi ", h.text)
        // Treffer stehen schon beim Tippen in der Leiste
        val hits = h.field<android.widget.LinearLayout>("searchHits")
        val shown = (0 until hits.childCount).map { (hits.getChildAt(it) as android.widget.TextView).text.toString() }
        println("Emoji-Suche live: $shown")
        assertTrue("keine Treffer: $shown", "🍕" in shown)
        hits.getChildAt(shown.indexOf("🍕")).performClick()
        h.settle()
        assertEquals("Hi 🍕", h.text)
        // Enter zeigt alle Treffer groß im Emoji-Bereich
        h.tap(KeyType.ENTER)
        h.layout()
        val media = h.field<MediaPanel>("mediaPanel")
        assertEquals(View.VISIBLE, media.visibility)
        media.tapFirstEmoji()
        h.settle()
        assertEquals("Hi 🍕🍕", h.text)
    }

    @Test fun copiedTextIsOfferedForPasting() {
        val h = KeyboardHarness()
        h.type("code ")
        val cm = h.service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("", "482913"))
        h.settle()
        println("Zwischenablage-Vorschlag: " + h.suggestions())
        val i = h.suggestions().indexOfFirst { it.contains("482913") }
        assertTrue(i >= 0)
        h.pick(i)
        assertEquals("Code 482913", h.text)
    }

    @Test fun oneHandedModeStillHitsTheRightKeys() {
        val h = KeyboardHarness()
        val full = h.keyboard.width
        h.call("toggleOneHanded")
        h.layout()
        assertTrue(h.keyboard.width < full * 0.9)
        h.type("hallo du")
        assertEquals("Hallo du", h.text)
        h.call("toggleOneHanded")
        h.layout()
        assertEquals(full, h.keyboard.width)
    }

    @Test fun resizeByDraggingTheTopEdge() {
        val h = KeyboardHarness()
        h.call("startResize")
        val overlay = h.field<View>("resizeOverlay")
        val t = android.os.SystemClock.uptimeMillis()
        overlay.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 500f, 20f, 0))
        overlay.dispatchTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_MOVE, 500f, -120f, 0))
        overlay.dispatchTouchEvent(MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, 500f, -120f, 0))
        h.settle()
        println("Größe nach Ziehen: ${h.prefs.keyHeightPercent} %")
        assertTrue(h.prefs.keyHeightPercent in 115..140)
        // unten ziehen: Abstand zum Rand
        overlay.dispatchTouchEvent(MotionEvent.obtain(t, t + 100, MotionEvent.ACTION_DOWN, 500f, overlay.height - 20f, 0))
        overlay.dispatchTouchEvent(MotionEvent.obtain(t, t + 150, MotionEvent.ACTION_MOVE, 500f, overlay.height - 110f, 0))
        overlay.dispatchTouchEvent(MotionEvent.obtain(t, t + 160, MotionEvent.ACTION_UP, 500f, overlay.height - 110f, 0))
        h.settle()
        println("Abstand nach Ziehen: ${h.prefs.bottomPadDp} dp")
        assertTrue(h.prefs.bottomPadDp in 20..45)
        h.call("endResize")
        h.type("ok")
        assertEquals("Ok", h.text)
    }
}
