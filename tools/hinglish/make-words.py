"""Makes app/src/main/assets/hinglish-words.bin (gzip; not named .gz, which the Android build treats specially): casual Roman (Hinglish) spellings -> Devanagari,
so Hindi voices read Hinglish replies as Hindi ("main aapke liye" -> "मैं आपके लिए").

Spellings come from the 12,000 most common Hindi words (wordfreq, data CC BY-SA 4.0) spelled the
ways people type them (roman.py); spellings that are common English words are left out, and
curated.txt (hand-checked) overrides. Run: pip install wordfreq; python3 tools/hinglish/make-words.py
"""
import gzip, os, sys
sys.path.insert(0, os.path.dirname(__file__))
from roman import variants
from wordfreq import top_n_list, zipf_frequency

here = os.path.dirname(__file__)
words = {}
for w in top_n_list('hi', 12000):
    if any(ch.isascii() for ch in w):
        continue
    for v in variants(w):
        if len(v) >= 2:
            words.setdefault(v, w)
words = {k: v for k, v in words.items() if zipf_frequency(k, 'en') < 4.0}
for line in open(os.path.join(here, 'curated.txt'), encoding='utf-8'):
    p = line.split()
    if len(p) == 2:
        words[p[0]] = p[1]
out = os.path.join(here, '..', '..', 'app', 'src', 'main', 'assets', 'hinglish-words.bin')
with gzip.open(out, 'wt', encoding='utf-8') as f:
    f.write("# Hinglish spelling<TAB>Devanagari. Made by tools/hinglish/make-words.py from wordfreq's Hindi list (CC BY-SA 4.0).\n")
    for k in sorted(words):
        f.write(f"{k}\t{words[k]}\n")
print(len(words), "spellings ->", out)
