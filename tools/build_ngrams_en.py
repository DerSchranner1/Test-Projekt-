#!/usr/bin/env python3
"""Erzeugt app/src/main/assets/ngrams_en.tsv: englische Wortfolgen (wie build_ngrams.py für Deutsch).

Quelle: Tatoeba, englische Sätze (CC BY 2.0 FR)
        https://downloads.tatoeba.org/exports/per_language/eng/

Nebenbei bekommt words.tsv die englische Schreibweise:
  - rein englische Wörter direkt in Spalte 1 („Monday“, „I'm“)
  - Wörter, die es in beiden Sprachen gibt, in einer vierten Spalte („Art“ -> „art“, „i“ -> „I“)

Vorher build_ngrams.py laufen lassen (das schreibt words.tsv mit drei Spalten).

Aufruf: build_ngrams_en.py words.tsv eng_sentences.tsv ngrams_en.tsv heldout_en.txt
"""
import re
import sys
from collections import Counter, defaultdict

words_path, tatoeba_path, out_path, heldout_path = sys.argv[1:5]

rows = []            # [display, de, en]
vocab = {}           # lowercase -> Index (nur Wörter mit englischer Häufigkeit)
for line in open(words_path, encoding="utf-8"):
    p = line.rstrip("\n").split("\t")
    rows.append([p[0], int(p[1]), int(p[2])])
    if int(p[2]) > 0:
        vocab.setdefault(p[0].lower(), len(rows) - 1)
V = len(rows) + 2
START = len(rows)
SKIP = {"tom", "tom's", "mary", "mary's", "tatoeba", "sami", "sami's", "layla", "layla's", "fadil", "dania", "mennad", "baya", "yanni", "skura", "ziri", "rima"}

TOKEN = re.compile(r"[A-Za-z]+(?:['’][A-Za-z]+)*|[.!?…]+|\d+|[^\s\w]")

bi = Counter()
tri = Counter()
bi_cap = Counter()
tri_cap = Counter()
surface = defaultdict(Counter)


def feed(sentence):
    p2 = None
    p1 = START
    for m in TOKEN.finditer(sentence):
        t = m.group().replace("’", "'")
        c = t[0]
        if c in ".!?…":
            p2, p1 = None, START
            continue
        if not c.isalpha():
            if c.isdigit():
                p2, p1 = None, None
            continue
        low = t.lower()
        w = vocab.get(low)
        if w is None or low in SKIP:
            p2, p1 = None, None
            continue
        cap = t[0].isupper()
        if p1 != START:
            surface[w][t] += 1
        if p1 is not None:
            bi[p1 * V + w] += 1
            if cap and p1 != START:
                bi_cap[p1 * V + w] += 1
            if p2 is not None:
                tri[(p2 * V + p1) * V + w] += 1
                if cap:
                    tri_cap[(p2 * V + p1) * V + w] += 1
        p2, p1 = p1, w


heldout = []
n = 0
for line in open(tatoeba_path, encoding="utf-8"):
    parts = line.rstrip("\n").split("\t")
    if len(parts) < 3:
        continue
    n += 1
    if n % 300 == 0:
        heldout.append(parts[2])
        continue
    feed(parts[2])
print("Tatoeba (en):", n, "| Bigramme:", len(bi), "Trigramme:", len(tri), file=sys.stderr)
with open(heldout_path, "w", encoding="utf-8") as f:
    f.write("\n".join(heldout) + "\n")

# ---- Englische Schreibweise ---------------------------------------------------------------
en_form = {}
for w in vocab.values():
    low = rows[w][0].lower()
    forms = surface.get(w)
    best = low
    if forms:
        total = sum(forms.values())
        top, c = forms.most_common(1)[0]
        if total >= 5 and c >= 0.7 * total and top.lower() == low:
            best = top
    if low == "i" or low.startswith("i'"):
        best = "I" + low[1:]
    en_form[w] = best

with open(words_path, "w", encoding="utf-8") as f:
    for i, (disp, de, en) in enumerate(rows):
        if en > 0 and de == 0:
            f.write(f"{en_form[i]}\t{de}\t{en}\n")           # rein englisch
        elif en > 0 and en_form[i] != disp:
            f.write(f"{disp}\t{de}\t{en}\t{en_form[i]}\n")   # beide Sprachen, andere Schreibweise
        else:
            f.write(f"{disp}\t{de}\t{en}\n")


def name(i):
    return "<s>" if i == START else rows[i][0].lower()


def shown(w, count, capcount, at_start):
    base = en_form[w]
    if at_start or base != base.lower():
        return base
    if capcount >= 0.7 * count:
        return base[0].upper() + base[1:]
    return base


BI_TOP, BI_MIN, BI_CTX_MIN = 20, 3, 20
TRI_TOP, TRI_MIN, TRI_CTX_MIN = 6, 4, 16

bi_by = defaultdict(list)
for k, c in bi.items():
    bi_by[k // V].append((c, k % V))
tri_by = defaultdict(list)
for k, c in tri.items():
    tri_by[k // V].append((c, k % V))

lines_bi = lines_tri = 0
with open(out_path, "w", encoding="utf-8") as f:
    for p, lst in sorted(bi_by.items(), key=lambda x: -sum(c for c, _ in x[1])):
        total = sum(c for c, _ in lst)
        if total < BI_CTX_MIN:
            continue
        top = [(c, w) for c, w in sorted(lst, reverse=True)[: (60 if p == START else BI_TOP)] if c >= BI_MIN]
        if not top:
            continue
        f.write(f"{name(p)}\t{total}\t" + " ".join(
            f"{shown(w, c, bi_cap.get(p * V + w, 0), p == START)}:{c}" for c, w in top) + "\n")
        lines_bi += 1
    for ctx, lst in sorted(tri_by.items(), key=lambda x: -sum(c for c, _ in x[1])):
        total = sum(c for c, _ in lst)
        if total < TRI_CTX_MIN:
            continue
        top = [(c, w) for c, w in sorted(lst, reverse=True)[:TRI_TOP] if c >= TRI_MIN]
        if not top:
            continue
        f.write(f"{name(ctx // V)} {name(ctx % V)}\t{total}\t" + " ".join(
            f"{shown(w, c, tri_cap.get(ctx * V + w, 0), False)}:{c}" for c, w in top) + "\n")
        lines_tri += 1
print("Zeilen: Bigramm-Kontexte", lines_bi, "Trigramm-Kontexte", lines_tri, file=sys.stderr)
