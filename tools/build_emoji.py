#!/usr/bin/env python3
"""Erzeugt die Emoji-Daten der Tastatur aus den Daten, die auch Gboard verwendet.

Quellen:
  - googlefonts/emoji-metadata, emoji_17_0_ordering.json (Apache 2.0): Reihenfolge, Gruppen,
    Varianten (Hauttöne), welche Emojis es animiert gibt
  - Unicode CLDR, common/annotations{,Derived}/{de,en}.xml (Unicode-Lizenz): Namen und Suchwörter

Aufruf:
  build_emoji.py ordering.json de.xml de_derived.xml en.xml en_derived.xml words.tsv emoji.tsv emoji_words.tsv

Ausgabe:
  emoji.tsv        Gruppe <TAB> Emoji <TAB> animiert(0/1) <TAB> Varianten <TAB> Suchwörter
  emoji_words.tsv  Wort <TAB> Emoji   (für Emoji-Vorschläge beim Tippen)
"""
import json
import re
import sys
from collections import defaultdict

ordering, de, de_d, en, en_d, words_path, out_path, out_words = sys.argv[1:9]

ANNOT = re.compile(r'<annotation cp="([^"]+)"( type="tts")?>([^<]*)</annotation>')


def load(path, names, keys):
    for cp, tts, text in ANNOT.findall(open(path, encoding="utf-8").read()):
        text = text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        if tts:
            names.setdefault(cp, text.strip())
        else:
            keys[cp] += [k.strip() for k in text.split("|") if k.strip()]


names_de, names_en = {}, {}
keys_de, keys_en = defaultdict(list), defaultdict(list)
load(de, names_de, keys_de); load(de_d, names_de, keys_de)
load(en, names_en, keys_en); load(en_d, names_en, keys_en)


def lookup(table, s):
    return table.get(s) or table.get(s.replace("️", ""))


# Sehr häufige Wörter bekommen keinen Emoji-Vorschlag (sonst stünde ständig einer in der Leiste)
common = set()
for line in open(words_path, encoding="utf-8"):
    p = line.rstrip("\n").split("\t")
    if int(p[1]) > 560 or int(p[2]) > 600:
        common.add(p[0].lower())

groups = json.load(open(ordering, encoding="utf-8"))
word_to_emoji = {}
keyword_count = defaultdict(int)
rows = []
for gi, g in enumerate(groups):
    for e in g["emoji"]:
        s = "".join(chr(c) for c in e["base"])
        alts = ["".join(chr(c) for c in a) for a in e.get("alternates", [])]
        alts = [a for a in alts if a != s]
        nde, nen = lookup(names_de, s), lookup(names_en, s)
        kw = []
        for k in [nde] + (lookup(keys_de, s) or []) + [nen] + (lookup(keys_en, s) or []):
            if k and k.lower() not in kw:
                kw.append(k.lower())
        rows.append((gi, s, 1 if e.get("animated") else 0, alts, kw, nde, nen))
        for k in set(kw):
            keyword_count[k] += 1

def usable(w):
    return " " not in w and len(w) >= 3 and w not in common and w.isalpha()


# 1. Durchgang: der Name des Emojis („Pizza“, „Hund“) gewinnt immer
for gi, s, anim, alts, kw, nde, nen in rows:
    if gi == 8:      # Flaggen nicht beim Tippen vorschlagen
        continue
    for n in (nde, nen):
        if n and usable(n.lower()):
            word_to_emoji.setdefault(n.lower(), s)
# 2. Durchgang: seltene, eindeutige Suchwörter
for gi, s, anim, alts, kw, nde, nen in rows:
    if gi == 8:
        continue
    for k in kw:
        if keyword_count[k] <= 2 and usable(k):
            word_to_emoji.setdefault(k, s)

with open(out_path, "w", encoding="utf-8") as f:
    for gi, s, anim, alts, kw, _, _ in rows:
        f.write(f"{gi}\t{s}\t{anim}\t{' '.join(alts)}\t{'|'.join(kw)}\n")
with open(out_words, "w", encoding="utf-8") as f:
    for w, s in sorted(word_to_emoji.items()):
        f.write(f"{w}\t{s}\n")
print("Emojis:", len(rows), "| animiert:", sum(r[2] for r in rows), "| Wörter:", len(word_to_emoji), file=sys.stderr)
