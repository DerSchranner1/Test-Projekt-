#!/usr/bin/env python3
"""Erzeugt app/src/main/assets/words.tsv: Wort, Häufigkeit Deutsch, Häufigkeit Englisch.

Quellen: wordfreq (Häufigkeiten, CC BY-SA 4.0) und igerman98/hunspell-de (Schreibweise, GPL).
Aufruf: build_wordlist.py hunspell_de_expanded.txt large_de.msgpack.gz large_en.msgpack.gz words.tsv
(hunspell_de_expanded.txt entsteht mit `unmunch de_DE.dic de_DE.aff`.)
"""
import gzip, msgpack, sys, re
def load(p):
    d = msgpack.load(gzip.open(p), raw=False)
    out = []
    for i, bucket in enumerate(d[1:]):
        for w in bucket:
            out.append((w, i))
    return out
hun = set(l.strip() for l in open(sys.argv[1], encoding='utf-8'))
hunlow = set(w for w in hun if w[:1].islower())
alpha = re.compile(r"^[a-zäöüßàâçéèêëîïôûùÿñæœ]+(?:[-'][a-zäöüß]+)?$")
de = load(sys.argv[2]); en = load(sys.argv[3])
import itertools
def variants(w):
    # wordfreq faltet ß zu ss – alle Kombinationen zurückbilden
    parts = w.split('ss')
    if len(parts) == 1 or len(parts) > 4: return [w]
    out = []
    for combo in itertools.product(['ss', 'ß'], repeat=len(parts)-1):
        x = parts[0]
        for sep, p in zip(combo, parts[1:]): x += sep + p
        out.append(x)
    return out
def forms(w):
    cap = w[:1].upper() + w[1:]
    in_low, in_cap = w in hunlow, cap in hun
    if in_cap and not in_low: return [cap]
    if in_low: return [w]
    return []
def compound(w):
    # Zusammengesetzte Wörter: Kopf + Schwanz bekannt? Groß, wenn der Schwanz ein Hauptwort ist
    for i in range(3, len(w) - 2):
        head, tail = w[:i], w[i:]
        hs = [head, head[:-1]] if head.endswith('s') else [head]
        if not any(h in hunlow or (h[:1].upper()+h[1:]) in hun for h in hs if len(h) >= 3): continue
        tcap = tail[:1].upper() + tail[1:]
        if tcap in hun: return w[:1].upper() + w[1:]
        if tail in hunlow: return w
    return None
SHORT_OK = set("""kg mg ml cm mm km qm dm hm ah oh hä hi ok zb lg vg mfg hdl xd pc tv cd dj wg ag eu wm em
usw bzw ggf evt inkl zzgl vlt gn gm ka kp nö jo ne na tja hmm omg lol wtf btw sms app
""".split())
res = {}
n = 0
for w, b in de:
    if not alpha.match(w) or len(w) > 30: continue
    found = []
    for v in variants(w): found += forms(v)
    if not found:
        for v in variants(w):
            c = compound(v)
            if c: found = [c]; break
    if not found:
        if b > 520: continue  # seltene unbekannte Wörter weglassen
        # Kurze Buchstabenfolgen, die das Wörterbuch nicht kennt („dt“, „df“, „ge“), sind fast immer
        # Bruchstücke – nur behalten, wenn sie wirklich häufig sind oder bekannte Kürzel
        if len(w) <= 2 and b > 440 and w not in SHORT_OK: continue
        if len(w) == 3 and b > 490 and w not in SHORT_OK: continue
        found = [w]
    score = max(1, 900 - b)
    for form in found:
        if form not in res: res[form] = score; n += 1
    if n >= 90000: break
# Englisch: eigene Häufigkeit in einer dritten Spalte (für Deutsch und Englisch gleichzeitig)
res = {form: [score, 0] for form, score in res.items()}
bylow = {}
for form in res:
    bylow.setdefault(form.lower(), form)
alpha_en = re.compile(r"^[a-z]+(?:'[a-z]+)?$")
m = 0
for w, b in en:
    if not alpha_en.match(w) or len(w) > 20: continue
    if len(w) <= 2 and b > 420 and w not in bylow: continue   # seltene englische Kürzel weglassen
    es = max(1, 900 - b)
    form = bylow.get(w)
    if form is not None:
        res[form][1] = max(res[form][1], es)
    else:
        res[w] = [0, es]
        bylow[w] = w
    m += 1
    if m >= 30000: break
with open(sys.argv[4], 'w', encoding='utf-8') as f:
    for w, (d, e) in sorted(res.items(), key=lambda x: -max(x[1][0], x[1][1] - 150)):
        f.write(f"{w}\t{d}\t{e}\n")
print(n, m, len(res))
