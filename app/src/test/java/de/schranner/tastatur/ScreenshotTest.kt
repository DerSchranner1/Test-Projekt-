package de.schranner.tastatur

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Rendert die Tastatur ohne Handy als PNG nach app/build/screenshots/ (zum Ansehen und Vergleichen). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w384dp-h832dp-450dpi")
class ScreenshotTest {

    @Test fun emptyFieldShowsToolbar() {
        KeyboardHarness().screenshot("1_leer")
    }

    @Test fun typing() {
        val h = KeyboardHarness()
        h.type("vielen ")
        h.screenshot("2_vorschlaege")
        h.type("dank fuer d")
        h.screenshot("3_tippen")
    }

    @Test fun umlautLayout() {
        KeyboardHarness { it.layout = "qwertz_umlaut"; it.eszettKey = true }.screenshot("4_umlaute")
    }

    @Test fun eszettKey() {
        KeyboardHarness { it.eszettKey = true; it.numberRow = false }.screenshot("5_eszett_ohne_zahlen")
    }

    @Test fun large() {
        KeyboardHarness { it.keyHeightPercent = 140 }.screenshot("6_gross")
    }

    private fun showPanel(h: KeyboardHarness, index: Int) {
        val panels = org.robolectric.util.ReflectionHelpers.getField<Any>(h.service, "panel").javaClass.enumConstants!!
        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Any>(
            h.service, "showPanel",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(panels[index].javaClass, panels[index]),
        )
        h.settle()
    }

    @Test fun panels() {
        val h = KeyboardHarness()
        h.type("hallo")
        showPanel(h, 3); h.screenshot("11_textbearbeitung")
        showPanel(h, 2); h.screenshot("12_zwischenablage")
        showPanel(h, 1); h.screenshot("13_emoji")
        val media = org.robolectric.util.ReflectionHelpers.getField<MediaPanel>(h.service, "mediaPanel")
        media.search(MediaPanel.Tab.EMOJI, "katze"); h.settle(); h.screenshot("16_emoji_suche")
        media.select(MediaPanel.Tab.GIF); h.settle(); h.screenshot("17_gif_ohne_schluessel")
        media.select(MediaPanel.Tab.STICKER); h.settle(); h.screenshot("18_sticker")
    }

    @Test fun typoIsUnderlined() {
        val h = KeyboardHarness()
        h.type("das ist ein fehelr im text")
        val v = h.edit
        v.setTextColor(android.graphics.Color.WHITE)
        v.setBackgroundColor(android.graphics.Color.parseColor("#17181A"))
        v.textSize = 22f
        v.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        val bmp = android.graphics.Bitmap.createBitmap(v.measuredWidth, v.measuredHeight, android.graphics.Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))
        java.io.File("build/screenshots").mkdirs()
        java.io.File("build/screenshots/15_tippfehler.png").outputStream().use {
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        // Rote Pixel = Unterstreichung des Tippfehlers
        var red = 0
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            val c = bmp.getPixel(x, y)
            if (android.graphics.Color.red(c) > 180 && android.graphics.Color.green(c) < 110 && android.graphics.Color.blue(c) < 110) red++
        }
        println("Tippfehler-Markierung: $red rote Pixel")
        org.junit.Assert.assertTrue(red > 50)
    }

    @Test fun settings() {
        val activity = org.robolectric.Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val view = activity.window.decorView
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(5200, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1080, 5200)
        val bmp = android.graphics.Bitmap.createBitmap(1080, 5200, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        java.io.File("build/screenshots").mkdirs()
        java.io.File("build/screenshots/14_einstellungen.png").outputStream().use {
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun tools() {
        val h = KeyboardHarness()
        h.call("startResize")
        h.screenshot("7_groesse")
        h.call("endResize")
        h.call("toggleOneHanded")
        h.screenshot("8_einhand")
        h.call("toggleOneHanded")
        h.tap(KeyType.SYMBOLS)
        h.screenshot("9_symbole")
    }

    @Test fun editPanel() {
        val h = KeyboardHarness()
        h.type("hallo")
        h.clickAll { it is android.widget.ImageView && it.parent === org.robolectric.util.ReflectionHelpers.getField<android.view.View>(h.service, "suggRow") }
        h.screenshot("10_werkzeuge")
    }
}
