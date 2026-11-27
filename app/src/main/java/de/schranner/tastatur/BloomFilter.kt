package de.schranner.tastatur

import java.io.DataInputStream
import java.io.InputStream

/**
 * Platzsparende Menge aller gültigen Wortformen (rund 500.000, Deutsch und Englisch) in unter 1 MB.
 * „Enthalten“ kann in etwa 0,3 % der Fälle fälschlich Ja sagen, aber nie fälschlich Nein.
 * Erzeugt von tools/build_spell.py – die Hash-Funktion muss dort identisch sein.
 */
class BloomFilter(private val bits: ByteArray, private val m: Long, private val k: Int) {

    fun contains(word: String): Boolean {
        var h1 = 0x811C9DC5L
        var h2 = 0x01000193L
        for (b in word.toByteArray(Charsets.UTF_8)) {
            val byte = (b.toInt() and 0xFF).toLong()
            h1 = ((h1 xor byte) * 0x01000193L) and 0xFFFFFFFFL
            h2 = ((h2 xor byte) * 0x85EBCA6BL) and 0xFFFFFFFFL
            h2 = (h2 xor (h2 ushr 13)) and 0xFFFFFFFFL
        }
        h2 = h2 or 1L
        for (i in 0 until k) {
            val idx = (h1 + i * h2) % m
            if ((bits[(idx ushr 3).toInt()].toInt() ushr (idx and 7).toInt()) and 1 == 0) return false
        }
        return true
    }

    companion object {
        fun read(input: InputStream): BloomFilter = DataInputStream(input.buffered()).use { d ->
            val magic = ByteArray(4)
            d.readFully(magic)
            require(String(magic) == "KBF1") { "kein Bloom-Filter" }
            val m = d.readInt().toLong() and 0xFFFFFFFFL
            val k = d.readInt()
            val bits = ByteArray((m / 8).toInt())
            d.readFully(bits)
            BloomFilter(bits, m, k)
        }
    }
}
