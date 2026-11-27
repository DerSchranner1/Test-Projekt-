package de.schranner.tastatur

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SuggestionEngineTest {

    companion object {
        lateinit var engine: SuggestionEngine
        lateinit var dir: File

        @BeforeClass @JvmStatic
        fun setUp() {
            dir = Files.createTempDirectory("engine").toFile()
            engine = SuggestionEngine(
                { File("src/main/assets/words.tsv").inputStream() }, dir, null, null,
                { name -> File("src/main/assets/$name").inputStream() },
            )
            engine.loadBlocking()
        }
    }

    private fun top(typed: String, prev: String? = null) = engine.suggest(typed, prev).also { println("$typed -> $it") }

    @Test fun completesWords() {
        assertTrue(top("Hal").take(3).contains("Hallo"))
        assertTrue(top("vielle").take(3).any { it == "vielleicht" })
    }

    @Test fun correctsTypos() {
        assertEquals("hallo", top("hallp").first())
        assertTrue(top("fehelr").take(3).contains("Fehler"))
        assertTrue(top("strasse").take(3).contains("Straße"))
        assertTrue(top("uber").take(3).contains("über"))
        assertTrue(engine.correctionsFor("Bahnhfo", null).contains("Bahnhof"))
    }

    @Test fun nounsAreCapitalized() {
        assertTrue(top("hau").take(3).contains("Haus"))
    }

    @Test fun knowsWords() {
        assertTrue(engine.isKnown("Haus"))
        assertTrue(engine.isKnown("Haustür"))
        assertTrue(engine.isKnown("Fußballverein"))
        assertTrue(engine.isKnown("okay"))
        assertFalse(engine.isKnown("Fehelr"))
        assertFalse(engine.isKnown("dsas"))
    }

    @Test fun learnsWordsAndNextWords() {
        // Ein Name ohne ähnliches bekanntes Wort wird nach zweimal Schreiben von selbst gelernt
        assertFalse(engine.isKnown("Qwzirbl"))
        engine.learn("Qwzirbl", null, true)
        engine.learn("Qwzirbl", "Hallo", false)
        assertTrue(engine.isKnown("Qwzirbl"))
        engine.learn("Kaffee", "trinken", false)
        engine.learn("Kaffee", "trinken", false)
        assertEquals("Kaffee", engine.predictNext("trinken", false).first())
    }

    @Test fun typosAreNotLearnedByRepeatingThem() {
        // „fehelr“ sieht aus wie „Fehler“: zweimal vertippen macht daraus kein gültiges Wort …
        repeat(4) { engine.learn("fehelr", "ein", false) }
        assertFalse(engine.isKnown("fehelr"))
        // … bewusst aufnehmen geht aber
        engine.addWord("Schranzl")
        assertTrue(engine.isKnown("Schranzl"))
    }

    @Test fun rareValidWordsAreKnownAndCommonMisspellingsAreNot() {
        for (w in listOf("abgekanzelt", "unbeschreiblich", "serendipity", "Zwetschge", "quintessential", "rausgehen")) {
            assertTrue(w, engine.isKnown(w))
        }
        for (w in listOf("vieleicht", "vorraus", "wiederspiegeln", "desshalb", "unterkringelln", "Fehelr", "nichtt")) {
            assertFalse(w, engine.isKnown(w))
        }
        assertTrue(engine.correctionsFor("vieleicht", null).first() == "vielleicht")
    }

    @Test fun verbsBecomeNounsInContext() {
        // Nach „beim“, „zum“ … wird das Verb groß geschrieben
        assertTrue(top("lauf", "beim").contains("Laufen"))
        assertEquals("Unterkringeln", engine.correctionsFor("unterkringelln", "beim").let {
            engine.addWord("unterkringeln"); engine.correctionsFor("unterkringelln", "beim").first()
        })
        // Nach „das“ ist beides möglich: beide Schreibweisen werden angeboten
        val both = engine.correctionsFor("unterkringelln", "Des")
        println("Des unterkringelln -> $both")
        assertTrue(both.contains("Unterkringeln") && both.contains("unterkringeln"))
        // Im Nachhinein: „Des unterkringeln funktioniert“ -> „Unterkringeln“
        assertEquals("Unterkringeln", engine.nounFromVerb("Des", "unterkringeln", "funktioniert"))
        assertEquals("Essen", engine.nounFromVerb("Das", "essen", "schmeckt"))
        assertEquals(null, engine.nounFromVerb("Das", "machen", "wir"))
        assertEquals(null, engine.nounFromVerb("Das", "stimmt", "nicht"))
        // Häufige Wendungen bleiben, wie sie in echten Texten stehen
        assertTrue(top("ein", "zum").contains("einen"))
    }

    @Test fun speed() {
        val t = System.nanoTime()
        for (w in listOf("abent", "bescheit", "morgn", "verstendnis", "telefoniren")) top(w)
        val ms = (System.nanoTime() - t) / 1_000_000
        println("5 Abfragen: $ms ms")
        assertTrue(ms < 2000)
    }
}
