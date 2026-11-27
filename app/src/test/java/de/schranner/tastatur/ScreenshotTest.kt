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
        media.select(MediaPanel.Tab.GIF); h.settle()
        // Eingebaute Animationen werden im Hintergrund dekodiert
        repeat(20) { Thread.sleep(50); h.settle() }
        h.screenshot("17_gif_ohne_schluessel")
        media.select(MediaPanel.Tab.STICKER); h.settle(); h.screenshot("18_sticker")
    }

    @Test fun emojiSearchLive() {
        val h = KeyboardHarness()
        h.tap(KeyType.EMOJI)
        h.layout()
        h.clickAll { it is android.widget.TextView && it.text.toString() == "🔍" }
        h.type("katze")
        h.screenshot("20_emoji_suche_live")
    }

    @Test fun emojiSuggestion() {
        val h = KeyboardHarness { it.emojiSuggestions = true }
        h.type("alles gute zum geburtstag ")
        h.screenshot("21_emoji_vorschlag")
    }

    @Test fun touchAssistDebug() {
        val h = KeyboardHarness { it.debugTouch = true; it.touchAssist = 60 }
        h.type("ich hab")
        val e = h.letter('e')
        // leicht daneben tippen, damit man die Umlenkung sieht
        h.tapKey(e, dx = 0.45f)
        h.backspace()
        h.settle()
        h.screenshot("22_treffhilfe_debug")
    }

    @Test fun touchAssistDebugSpace() {
        val h = KeyboardHarness { it.debugTouch = true; it.touchAssist = 60 }
        h.type("ich")
        h.screenshot("24_treffhilfe_leertaste")
        h.prefs.debugTouch = false
    }

    @Test fun blueHint() {
        val h = KeyboardHarness { it.markTypos = true; it.autocorrect = false }
        h.type("ich fahre das auto ")
        h.screenshot("26_blau_unterkringelt")
    }

    @Test fun logo() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val size = 432
        val bmp = android.graphics.Bitmap.createBitmap(size * 2 + 40, size, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(android.graphics.Color.parseColor("#202124"))
        // Wie auf dem Startbildschirm: Hintergrund und Vordergrund, rund maskiert
        val layer = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val lc = android.graphics.Canvas(layer)
        for (id in listOf(R.drawable.ic_launcher_background, R.drawable.ic_launcher_foreground)) {
            ctx.getDrawable(id)!!.apply { setBounds(0, 0, size, size); draw(lc) }
        }
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.BitmapShader(layer, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
        val inset = size * 18f / 108f
        c.drawCircle(size / 2f, size / 2f, size / 2f - inset, p)
        c.save(); c.translate(size + 40f, 0f)
        c.drawRoundRect(inset, inset, size - inset, size - inset, size * 0.12f, size * 0.12f, p)
        c.restore()
        val dir = java.io.File("build/screenshots").apply { mkdirs() }
        java.io.File(dir, "00_logo.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun squiggleInStrip() {
        val h = KeyboardHarness()
        h.type("das ist ein fehelr ")
        h.screenshot("19_kringel")
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

    private fun settingsCrop(title: String, file: String, height: Int, before: Int = 120) {
        val activity = org.robolectric.Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val view = activity.window.decorView
        val h = 9000
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1080, h)
        fun find(v: android.view.View): android.view.View? {
            if (v is android.widget.TextView && v.text.toString() == title) return v
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        val t = find(view)!!
        val loc = IntArray(2); t.getLocationInWindow(loc)
        val top = (loc[1] - before).coerceAtLeast(0)
        val bmp = android.graphics.Bitmap.createBitmap(1080, height, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.translate(0f, -top.toFloat())
        view.draw(c)
        java.io.File("build/screenshots").mkdirs()
        java.io.File("build/screenshots/$file.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun settingsHeader() {
        settingsCrop("KlarTastatur", "27_einstellungen_kopf", 420, 60)
    }

    @Test fun dictationSettings() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val p = Prefs(app)
        p.groqKey = "gsk_test"; p.useGroq = true
        // so tun, als wären beide Modelle geladen
        val w = OfflineWhisper(app)
        for (m in OfflineWhisper.Model.entries) java.io.RandomAccessFile(w.file(m), "rw").use { it.setLength(m.bytes) }
        // Im Test gibt es die native Bibliothek nicht – für das Bild so tun, als ob
        org.robolectric.util.ReflectionHelpers.setField(WhisperNative, "loaded", true)
        settingsCrop("So wird erkannt", "25_diktieren", 2300, 200)
        org.robolectric.util.ReflectionHelpers.setField(WhisperNative, "loaded", null)
        for (m in OfflineWhisper.Model.entries) w.file(m).delete()
        p.groqKey = ""; p.useGroq = false
    }

    @Test fun debugGuide() {
        Prefs(org.robolectric.RuntimeEnvironment.getApplication()).debugTouch = true
        val activity = org.robolectric.Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val view = activity.window.decorView
        val h = 9000
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 1080, h)
        // Die Anleitung finden und nur diesen Teil abbilden
        fun find(v: android.view.View): android.view.View? {
            if (v is android.widget.TextView && v.text.toString() == "So liest du die Anzeige") return v
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            return null
        }
        val title = find(view)!!
        val loc = IntArray(2); title.getLocationInWindow(loc)
        val top = (loc[1] - 260).coerceAtLeast(0)
        val bmp = android.graphics.Bitmap.createBitmap(1080, 2050, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.translate(0f, -top.toFloat())
        view.draw(c)
        java.io.File("build/screenshots").mkdirs()
        java.io.File("build/screenshots/23_treffhilfe_anleitung.png").outputStream().use {
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        Prefs(org.robolectric.RuntimeEnvironment.getApplication()).debugTouch = false
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
