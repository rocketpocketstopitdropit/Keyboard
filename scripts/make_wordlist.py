"""Builds app/src/main/assets/wordlist.txt from wordfreq.

One word per line, most common first, with its Zipf frequency after a tab
("the\t7.73"). WordPredictor uses those numbers as real word frequencies.

Runs during the GitHub Actions build (pip install wordfreq first), so the
list never has to be pasted into the repo by hand.

Usage: python scripts/make_wordlist.py [output path] [word count]
"""
import re
import sys

from wordfreq import iter_wordlist, zipf_frequency

OUT = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/wordlist.txt"
COUNT = int(sys.argv[2]) if len(sys.argv) > 2 else 100_000

# Plain words: letters, optionally with one inner apostrophe ("don't").
WORD = re.compile(r"^[a-z]+(?:'[a-z]+)?$")
TRIPLE = re.compile(r"(.)\1\1")

# Apostrophe-less spellings the autocorrector turns into contractions.
# If they were in the dictionary they would count as real words and never
# get fixed, so leave them out.
SKIP = {
    "im", "ive", "dont", "cant", "didnt", "doesnt", "isnt", "wasnt",
    "couldnt", "wouldnt", "shouldnt", "havent", "hasnt", "arent", "werent",
    "youre", "theyre", "thats", "whats", "theres",
}
# The only real one-letter words.
ONE_LETTER = {"a", "i"}

written = 0
with open(OUT, "w", encoding="utf-8", newline="\n") as f:
    for w in iter_wordlist("en", "large"):
        w = w.replace("’", "'")
        if len(w) > 24 or not WORD.match(w) or TRIPLE.search(w):
            continue
        if w in SKIP or (len(w) == 1 and w not in ONE_LETTER):
            continue
        z = zipf_frequency(w, "en", "large")
        if z <= 0:
            continue
        f.write(f"{w}\t{z:.2f}\n")
        written += 1
        if written >= COUNT:
            break

print(f"Wrote {written} words to {OUT}")
