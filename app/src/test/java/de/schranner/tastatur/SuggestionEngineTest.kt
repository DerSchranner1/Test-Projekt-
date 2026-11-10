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
            engine = SuggestionEngine({ File("src/main/assets/words.tsv").inputStream() }, dir)
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
        assertFalse(engine.isKnown("Schranzl"))
        engine.learn("Schranzl", null, true)
        engine.learn("Schranzl", "Hallo", false)
        assertTrue(engine.isKnown("Schranzl"))
        engine.learn("Kaffee", "trinken", false)
        engine.learn("Kaffee", "trinken", false)
        assertEquals("Kaffee", engine.predictNext("trinken", false).first())
    }

    @Test fun speed() {
        val t = System.nanoTime()
        for (w in listOf("abent", "bescheit", "morgn", "verstendnis", "telefoniren")) top(w)
        val ms = (System.nanoTime() - t) / 1_000_000
        println("5 Abfragen: $ms ms")
        assertTrue(ms < 2000)
    }
}
