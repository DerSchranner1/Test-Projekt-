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
        found = [w]
    score = max(1, 900 - b)
    for form in found:
        if form not in res: res[form] = score; n += 1
    if n >= 90000: break
m = 0
for w, b in en:
    if not alpha.match(w) or len(w) > 20: continue
    if w in res or (w[:1].upper()+w[1:]) in res: continue
    res[w] = max(1, 700 - b)  # Englisch etwas schwächer gewichten
    m += 1
    if m >= 15000: break
with open(sys.argv[4], 'w', encoding='utf-8') as f:
    for w, s in sorted(res.items(), key=lambda x: -x[1]):
        f.write(f"{w}\t{s}\n")
print(n, m, len(res))
