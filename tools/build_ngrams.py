#!/usr/bin/env python3
"""Erzeugt app/src/main/assets/ngrams.tsv: welche Wörter typischerweise aufeinander folgen.

Quellen (nicht im Repo, werden als Argumente übergeben):
  - Tatoeba, deutsche Sätze (CC BY 2.0 FR)   https://downloads.tatoeba.org/exports/per_language/deu/
  - Mozilla Common Voice, Satzsammlung (CC0)  server/data/de/sentence-collector.txt
  - Mozilla Common Voice, Wikipedia-Sätze     server/data/de/wiki.de.txt

Nebenbei wird die Großschreibung in words.tsv verbessert (Namen und Abkürzungen wie „USA“,
„WhatsApp“), und Folgewörter bekommen ihre Schreibweise im Zusammenhang („Vielen Dank“).

Aufruf:
  build_ngrams.py words.tsv tatoeba.tsv sentence-collector.txt wiki.de.txt ngrams.tsv heldout.txt \
                  hunspell_de_expanded.txt en_top20k.txt
"""
import re
import sys
from collections import Counter, defaultdict

words_path, tatoeba_path, cv_path, wiki_path, out_path, heldout_path, hun_path, en_path = sys.argv[1:9]

vocab = {}
display = []          # Schreibweise laut words.tsv
word_lines = []
for line in open(words_path, encoding="utf-8"):
    w, score = line.rstrip("\n").split("\t", 1)     # score = "deutsch<TAB>englisch"
    word_lines.append([w, score])
    if w.lower() not in vocab:
        vocab[w.lower()] = len(vocab)
        display.append(w)
ids = list(vocab.keys())
hunlow = set(l.strip() for l in open(hun_path, encoding="utf-8") if l[:1].islower())
en_rank = {w.strip(): i for i, w in enumerate(open(en_path, encoding="utf-8"))}

# Feste Schreibweisen für Marken und Abkürzungen
FIXED = {w.lower(): w for w in """
Google Facebook YouTube Instagram Netflix Amazon Microsoft Twitter Samsung Spotify TikTok PayPal
WhatsApp eBay iPhone iPad Wikipedia Android LinkedIn Snapchat Pinterest Tesla BMW VW Audi Mercedes
Lidl Aldi Rewe Edeka IKEA DHL USA EU WLAN DVD TV PC SMS CD GPS USB PDF LKW PKW ICE DB AG GmbH
""".split()}
V = len(ids) + 2
START = len(ids)        # Satzanfang
# Namen, die in Tatoeba extrem überrepräsentiert sind – sie würden sonst ständig vorgeschlagen
SKIP = {"tom", "toms", "maria", "marias", "johannes", "elke", "tatoeba"}

TOKEN = re.compile(r"[A-Za-zÄÖÜäöüßéèêàâçñ]+(?:['’-][A-Za-zÄÖÜäöüß]+)*|[.!?…]+|\d+|[^\s\w]")

uni = Counter()
bi = Counter()
tri = Counter()
bi_cap = Counter()     # davon großgeschrieben (nur wenn nicht am Satzanfang)
tri_cap = Counter()
surface = defaultdict(Counter)   # Wort -> Schreibweisen mitten im Satz


def feed(sentence, weight, starts=True):
    """starts=False: Satzanfänge nicht zählen (Wikipedia beginnt anders als Chat-Nachrichten)."""
    p2 = None
    p1 = START
    for m in TOKEN.finditer(sentence):
        t = m.group()
        c = t[0]
        if c in ".!?…":
            p2, p1 = None, START
            continue
        if not c.isalpha():
            if c.isdigit():
                p2, p1 = None, None
            continue  # Kommas, Anführungszeichen usw. sind durchlässig
        low = t.lower().replace("’", "'")
        w = vocab.get(low)
        if w is None or low in SKIP:
            p2, p1 = None, None
            continue
        uni[w] += weight
        cap = t[0].isupper()
        if p1 != START:
            surface[w][t] += 1
        if p1 is not None and (starts or p1 != START):
            bi[p1 * V + w] += weight
            if cap and p1 != START:
                bi_cap[p1 * V + w] += weight
            if p2 is not None and (starts or p2 != START):
                tri[(p2 * V + p1) * V + w] += weight
                if cap:
                    tri_cap[(p2 * V + p1) * V + w] += weight
        p2, p1 = p1, w


heldout = []
n = 0
for line in open(tatoeba_path, encoding="utf-8"):
    parts = line.rstrip("\n").split("\t")
    if len(parts) < 3:
        continue
    n += 1
    if n % 150 == 0:
        heldout.append(parts[2])
        continue
    feed(parts[2], 3)
print("Tatoeba:", n, file=sys.stderr)

n = 0
for line in open(cv_path, encoding="utf-8"):
    n += 1
    if n % 40 == 0:
        heldout.append(line.strip())
        continue
    feed(line, 3)
print("Common Voice:", n, file=sys.stderr)

n = 0
for line in open(wiki_path, encoding="utf-8"):
    n += 1
    feed(line, 1, starts=False)
print("Wikipedia:", n, "| Bigramme:", len(bi), "Trigramme:", len(tri), file=sys.stderr)

with open(heldout_path, "w", encoding="utf-8") as f:
    f.write("\n".join(heldout) + "\n")


# ---- Großschreibung im Wörterbuch verbessern -------------------------------------------------
changed = 0
for i, low in enumerate(ids):
    disp = display[i]
    new = None
    if low in FIXED:
        new = FIXED[low]
    elif disp == low and low not in hunlow and len(low) > 1:
        forms = surface.get(i)
        if forms:
            total = sum(forms.values())
            best, c = forms.most_common(1)[0]
            if total >= 8 and c >= 0.9 * total and best != low and best.lower() == low:
                simple = best[1:] == best[1:].lower()       # nur der erste Buchstabe groß
                r = en_rank.get(low)
                if r is None or (r >= 3000 and not simple):
                    new = best
    if new and new != disp:
        display[i] = new
        changed += 1
by_low = {low: display[i] for i, low in enumerate(ids)}
with open(words_path, "w", encoding="utf-8") as f:
    seen = set()
    for w, score in word_lines:
        low = w.lower()
        if low in seen:
            continue
        seen.add(low)
        f.write(f"{by_low[low]}\t{score}\n")
print("Schreibweise korrigiert:", changed, file=sys.stderr)


def name(i):
    return "<s>" if i == START else ids[i]


def shown(w, count, capcount, at_start):
    """Schreibweise eines Folgeworts in diesem Zusammenhang (z. B. „Dank“ nach „vielen“)."""
    disp = display[w]
    if at_start or disp != ids[w]:
        return disp
    if capcount >= 0.7 * count:
        return disp[0].upper() + disp[1:]
    return disp


BI_TOP, BI_MIN, BI_CTX_MIN = 24, 4, 30
TRI_TOP, TRI_MIN, TRI_CTX_MIN = 6, 5, 20

bi_by = defaultdict(list)
for k, c in bi.items():
    bi_by[k // V].append((c, k % V))
tri_by = defaultdict(list)
for k, c in tri.items():
    tri_by[k // V].append((c, k % V))

lines_bi = 0
lines_tri = 0
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
