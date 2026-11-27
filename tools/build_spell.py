#!/usr/bin/env python3
"""Erzeugt die Daten für die Rechtschreibprüfung:

  valid.bloom     Bloom-Filter aller gültigen Wortformen (Deutsch: igerman98/hunspell, Englisch: SCOWL/hunspell-en).
                  Damit gelten auch seltene richtige Wörter als richtig, ohne dass sie Platz im Wörterbuch brauchen.
  grammar_de.tsv  Verb-Infinitive und Formen der 3. Person – für die Großschreibung von Hauptwörtern
                  aus Verben („das Unterkringeln funktioniert“).

Aufruf: build_spell.py de_expanded.txt en_expanded.txt verb_inf.txt verb_fin.txt valid.bloom grammar_de.tsv
Die expandierten Listen entstehen mit `unmunch xx.dic xx.aff`.
"""
import struct
import sys

de_path, en_path, inf_path, fin_path, bloom_path, grammar_path = sys.argv[1:7]

BITS_PER_WORD = 12
K = 8


def hashes(word):
    """Zwei 32-Bit-Hashes (FNV-1a mit verschiedenen Startwerten) – identisch in BloomFilter.kt."""
    h1 = 0x811C9DC5
    h2 = 0x01000193
    for byte in word.encode("utf-8"):
        h1 = ((h1 ^ byte) * 0x01000193) & 0xFFFFFFFF
        h2 = ((h2 ^ byte) * 0x85EBCA6B) & 0xFFFFFFFF
        h2 = (h2 ^ (h2 >> 13)) & 0xFFFFFFFF
    return h1, h2 | 1


words = set()
for path in (de_path, en_path):
    for line in open(path, encoding="utf-8", errors="replace"):
        w = line.strip().lower()
        if 1 < len(w) <= 40 and all(c.isalpha() or c in "'-" for c in w):
            words.add(w)
m = len(words) * BITS_PER_WORD
m += (-m) % 8
bits = bytearray(m // 8)
for w in words:
    h1, h2 = hashes(w)
    for i in range(K):
        idx = (h1 + i * h2) % m
        bits[idx >> 3] |= 1 << (idx & 7)
with open(bloom_path, "wb") as f:
    f.write(b"KBF1" + struct.pack(">II", m, K) + bytes(bits))

FINITE_EXTRA = """ist war hat wird kann muss soll will darf mag tut gibt nimmt sieht liest läuft fährt hält fällt
schläft trägt wächst weiß hilft spricht trifft wirft stirbt bricht isst vergisst lässt heißt bleibt kommt geht steht
liegt bringt denkt kennt nennt beginnt gewinnt schwimmt singt klingt riecht zieht fliegt schließt reißt beißt"""
inf = sorted(set(open(inf_path, encoding="utf-8").read().split()))
fin = sorted(set(open(fin_path, encoding="utf-8").read().split()) | set(FINITE_EXTRA.split()))
with open(grammar_path, "w", encoding="utf-8") as f:
    f.write("I\t" + " ".join(inf) + "\n")
    f.write("F\t" + " ".join(fin) + "\n")
print("Wortformen:", len(words), "| Filter:", len(bits) // 1024, "KB | Infinitive:", len(inf), "| 3. Person:", len(fin), file=sys.stderr)
