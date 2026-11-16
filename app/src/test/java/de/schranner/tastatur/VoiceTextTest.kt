package de.schranner.tastatur

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceTextTest {

    @Test fun spokenPunctuationIsConverted() {
        assertEquals("wie geht es dir? Gut", VoiceText.spokenPunctuation("wie geht es dir fragezeichen gut"))
        assertEquals("erstens, zweitens.", VoiceText.spokenPunctuation("erstens komma zweitens punkt"))
        assertEquals("hallo\nWie geht's", VoiceText.spokenPunctuation("hallo neue Zeile wie geht's"))
        assertEquals("super!", VoiceText.spokenPunctuation("super Ausrufezeichen"))
    }

    @Test fun punctuatedTextIsLeftAlone() {
        // Die Erkennung hat schon selbst Satzzeichen gesetzt
        assertEquals("Das Komma ist wichtig.", VoiceText.spokenPunctuation("Das Komma ist wichtig."))
        // „Punkt“ mitten im Satz bleibt ein Wort
        assertEquals("der punkt ist klar", VoiceText.spokenPunctuation("der punkt ist klar"))
    }

    @Test fun firstWordFitsContext() {
        val dict: (String) -> String? = { mapOf("das" to "das", "haus" to "Haus")[it.lowercase()] }
        assertEquals("Das ist gut", VoiceText.fitToContext("das ist gut", true, dict))
        assertEquals("das ist gut", VoiceText.fitToContext("Das ist gut", false, dict))
        assertEquals("Haus und Hof", VoiceText.fitToContext("Haus und Hof", false, dict))
    }

    @Test fun hapticsDifferByActionAndStrength() {
        val k = Haptics.Kind.KEY
        // Stärker eingestellt = kräftiger
        assert(Haptics.scale(k, 20) < Haptics.scale(k, 90))
        assert(Haptics.amplitude(k, 20) < Haptics.amplitude(k, 90))
        // Aktionen fühlen sich unterschiedlich an: Cursor-Schritt < Buchstabe < Leertaste < Aufklappen
        val s = 60
        assert(Haptics.scale(Haptics.Kind.TICK, s) < Haptics.scale(k, s))
        assert(Haptics.scale(k, s) < Haptics.scale(Haptics.Kind.SPECIAL, s))
        assert(Haptics.scale(Haptics.Kind.SPECIAL, s) < Haptics.scale(Haptics.Kind.POPUP, s))
        assertEquals(1f, Haptics.scale(Haptics.Kind.POPUP, 100), 0.001f)
    }
}
