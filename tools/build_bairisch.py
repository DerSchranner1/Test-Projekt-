#!/usr/bin/env python3
"""
Baut die bairischen Wortdaten der Tastatur.

  build_bairisch.py bairisch_basis.txt bar.txt words.tsv bairisch.tsv ngrams_bar.tsv

bairisch_basis.txt  handgeschriebene Alltagswörter und Wendungen (dieses Verzeichnis)
bar.txt             Text der bairischen Wikipedia (CC BY-SA), ein Artikel pro Zeile
words.tsv           das hochdeutsch/englische Wörterbuch der Tastatur
bairisch.tsv        Ausgabe: Wort <TAB> Häufigkeit (gleiche Skala wie words.tsv)
ngrams_bar.tsv      Ausgabe: Wortfolgen im Format von ngrams.tsv
"""
import collections, math, re, sys

basis, bar_txt, words_tsv, out_words, out_ngrams = sys.argv[1:6]

LEVEL = {5: 700, 4: 645, 3: 595, 2: 545, 1: 500}
TOKEN = re.compile(r"[A-Za-zÄÖÜäöüß]+(?:'[a-zäöü]+)?")

# --- Hochdeutsches Wörterbuch
de, en = {}, {}
for line in open(words_tsv, encoding='utf-8'):
    p = line.rstrip('\n').split('\t')
    try:
        de[p[0].lower()] = float(p[1])
        en[p[0].lower()] = float(p[2]) if len(p) > 2 and p[2] else 0.0
    except (ValueError, IndexError):
        pass

# --- Grundliste
curated, phrases = {}, []
noun_section = False
for raw in open(basis, encoding='utf-8'):
    line = raw.strip()
    if not line:
        continue
    if line.startswith('#'):
        noun_section = any(k in line for k in ('Leute', 'Essen'))
        continue
    if line.startswith('>'):
        phrases.append(line[1:].split())
        continue
    p = line.split()
    w = p[0]
    lvl = int(p[1]) if len(p) > 1 else 3
    curated[w.lower()] = (max(lvl, curated.get(w.lower(), (0, False))[0]), noun_section)

# --- Wikipedia zählen
counts = collections.Counter()
casing = collections.defaultdict(collections.Counter)
pairs = collections.Counter()
total = 0
for text in open(bar_txt, encoding='utf-8'):
    for sent in re.split(r'[.!?;:\n()\[\]"„“]+', text):
        toks = TOKEN.findall(sent)
        low = [t.lower() for t in toks]
        for t, l in zip(toks, low):
            counts[l] += 1
            casing[l][t] += 1
        total += len(toks)
        for a, b in zip(low, low[1:]):
            pairs[(a, b)] += 1

def cased(w, noun=False):
    c = casing.get(w)
    if c:
        best, n = c.most_common(1)[0]
        cap = sum(v for k, v in c.items() if k[:1].isupper())
        # Großgeschrieben nur, wenn es fast immer groß steht (Hauptwörter)
        if cap >= 0.85 * sum(c.values()) and sum(c.values()) >= 10:
            return w[:1].upper() + w[1:]
        return w
    return w[:1].upper() + w[1:] if noun else w

out = {}
for w, (lvl, noun) in curated.items():
    # Hauptwörter groß, alles andere (auch Grüße wie „servus“) klein wie im Chat
    out[w] = ((w[:1].upper() + w[1:]) if noun else w, LEVEL[lvl])

# Weitere bairische Wörter aus der Wikipedia: nur was es im Hochdeutschen nicht gibt
for w, n in counts.items():
    if n < 6 or len(w) < 2 or w in out:
        continue
    if de.get(w, 0) > 0 or en.get(w, 0) >= 500:
        continue
    if not re.fullmatch(r"[a-zäöüß]+(?:'[a-zäöü]+)?", w):
        continue
    score = min(620.0, 900 + 100 * math.log10(n / total) - 60)
    out[w] = (cased(w), round(score))

with open(out_words, 'w', encoding='utf-8') as f:
    for w, (form, s) in sorted(out.items(), key=lambda x: -x[1][1]):
        f.write(f"{form}\t{s}\n")

# --- Wortfolgen: Wendungen aus der Grundliste + häufige Paare aus der Wikipedia
def form(w):
    return out[w][0] if w in out else w

phr = collections.defaultdict(collections.Counter)
for toks in phrases:
    toks = [t.lower() for t in toks]
    seq = ['<s>'] + toks
    for i in range(1, len(seq)):
        phr[seq[i - 1]][toks[i - 1]] += 10
        if i >= 2:
            phr[seq[i - 2] + ' ' + seq[i - 1]][toks[i - 1]] += 10

wiki = collections.defaultdict(collections.Counter)
for (a, b), n in pairs.items():
    if n < 5 or a not in out:
        continue
    # Folgewort: bairisch oder hochdeutsch, aber kein englisches Wort („county“) und kein Bruchstück
    if len(b) < 2 and b not in ('i', 'a'):
        continue
    if b not in out and not (de.get(b, 0) > 0 and en.get(b, 0) < de.get(b, 0)):
        continue
    wiki[a][b] = n

keys = set(phr) | set(wiki)
with open(out_ngrams, 'w', encoding='utf-8') as f:
    for k in sorted(keys):
        share = collections.Counter()
        p, w = phr.get(k), wiki.get(k)
        pt = sum(p.values()) if p else 0
        wt = sum(w.values()) if w else 0
        for x, n in (p or {}).items():
            share[x] += (0.65 if w else 1.0) * n / pt
        if w:
            for x, n in w.most_common(12):
                share[x] += (0.35 if p else 1.0) * n / wt
        items = [(x, max(1, round(v * 1000))) for x, v in share.most_common(14)]
        tot = sum(c for _, c in items)
        f.write(k + '\t' + str(tot) + '\t' + ' '.join(f"{form(x)}:{c}" for x, c in items) + '\n')

print('Wörter:', len(out), 'davon Grundliste:', len(curated), 'Wortfolgen-Schlüssel:', len(keys))
