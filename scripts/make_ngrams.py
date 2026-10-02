"""Builds app/src/main/assets/ngrams.txt: which words follow which, counted
from real everyday sentences.

Input: Tatoeba's English sentences (CC BY 2.0 FR), one per line as
"id<TAB>lang<TAB>text", plain or .bz2.

Output (tab-separated), read by WordPredictor:
  C <context> <total> <distinct>  how much followed a context, and how many different words
  B <word> <next> <count>         word pair
  T <w1 w2> <next> <count>        word triple

Text is split into words the way the keyboard sees them: lowercase,
sentences start fresh after . ! ? (context "<s>"), commas and other
punctuation don't break the chain, and a word the keyboard doesn't know
does. Only words in the app's word lists are counted.

Usage: python scripts/make_ngrams.py <sentences> <out> [stats file]
"""
import bz2
import math
import re
import sys
from collections import Counter, defaultdict

SRC = sys.argv[1]
OUT = sys.argv[2]
STATS = sys.argv[3] if len(sys.argv) > 3 else None
ASSETS = "app/src/main/assets"
START = "<s>"

# Tatoeba's example sentences lean very heavily on a few stock characters
# (Tom, Mary, Sami, Layla...) and places; left in, they'd be top guesses
# after half the words in English. Any word used far more here than in
# English at large (wordfreq) is treated like an unknown word.
OVERUSE_FACTOR = 15        # this many times its normal frequency
OVERUSE_MIN_COUNT = 150    # ...and common enough here to matter

# Pruning, so the table stays small enough for a keyboard to hold in memory.
PAIR_MIN, PAIR_PER_CONTEXT, PAIR_CAP = 2, 60, 150_000
TRIPLE_CONTEXT_MIN, TRIPLE_MIN, TRIPLE_PER_CONTEXT, TRIPLE_CAP = 8, 2, 20, 150_000

WORD = re.compile(r"[a-z]+(?:'[a-z]+)?")
TOKEN = re.compile(r"[a-z]+(?:'[a-z]+)?|[.!?]+|[^\sa-z]")


def load_vocab():
    """Known words, and the Zipf frequency of each word wordlist.txt gives one for."""
    vocab = set()
    zipf = {}
    for name in ("wordlist.txt", "wordlist2.txt", "wordlist3.txt", "wordlist4.txt"):
        try:
            with open(f"{ASSETS}/{name}", encoding="utf-8") as f:
                for line in f:
                    parts = line.rstrip("\n").split("\t")
                    w = parts[0].strip().lower().replace("’", "'")
                    if not WORD.fullmatch(w):
                        continue
                    vocab.add(w)
                    if len(parts) > 1:
                        try:
                            zipf.setdefault(w, float(parts[1]))
                        except ValueError:
                            pass
        except FileNotFoundError:
            pass
    vocab.update(["i", "a", "i'm", "don't", "can't", "it's", "that's", "i'll", "i've", "i'd"])
    return vocab, zipf


def sentences(path):
    opener = bz2.open if path.endswith(".bz2") else open
    seen = set()
    with opener(path, "rt", encoding="utf-8", errors="replace") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            text = parts[2] if len(parts) >= 3 else parts[-1]
            text = text.lower().replace("’", "'").replace("‘", "'")
            if text in seen:
                continue
            seen.add(text)
            yield text


def overused(texts, vocab, zipf):
    """Words this corpus uses far more than English in general does, most used first."""
    counts = Counter()
    total = 0
    for text in texts:
        for tok in WORD.findall(text):
            total += 1
            if tok in vocab:
                counts[tok] += 1
    if total == 0:
        return []
    rarest = min(zipf.values()) if zipf else 1.0
    limit = math.log10(OVERUSE_FACTOR)
    out = []
    for w, c in counts.items():
        if c < OVERUSE_MIN_COUNT:
            continue
        here = math.log10(c / total * 1e9)
        if here - zipf.get(w, rarest) > limit:
            out.append((c, w))
    out.sort(reverse=True)
    return out


def prune(table, context_min, item_min, per_context, cap):
    stats = {}
    kept = []
    for ctx, row in table.items():
        total = sum(row.values())
        if total < context_min:
            continue
        stats[ctx] = (total, len(row))
        for w, c in row.most_common(per_context):
            if c < item_min:
                break
            kept.append((c, ctx, w))
    kept.sort(reverse=True)
    kept = kept[:cap]
    used = {ctx for _, ctx, _ in kept}
    return kept, {ctx: stats[ctx] for ctx in used}


def main():
    vocab, zipf = load_vocab()
    texts = list(sentences(SRC))
    dropped = overused(texts, vocab, zipf)
    vocab -= {w for _, w in dropped}
    print(f"vocabulary: {len(vocab)} words")

    pairs = defaultdict(Counter)
    triples = defaultdict(Counter)
    n_tok = 0

    for text in texts:
        p2, p1 = None, START
        for tok in TOKEN.findall(text):
            if tok[0] in ".!?":
                p2, p1 = None, START
                continue
            if not tok[0].isalpha():
                continue  # commas, quotes, digits: the keyboard keeps its context
            n_tok += 1
            if tok not in vocab:
                p2, p1 = None, None  # unknown word: nothing reliable follows from it
                continue
            if p1 is not None:
                pairs[p1][tok] += 1
                if p2 is not None:
                    triples[f"{p2} {p1}"][tok] += 1
            p2, p1 = p1, tok

    pair_rows, pair_ctx = prune(pairs, PAIR_MIN, PAIR_MIN, PAIR_PER_CONTEXT, PAIR_CAP)
    tri_rows, tri_ctx = prune(triples, TRIPLE_CONTEXT_MIN, TRIPLE_MIN, TRIPLE_PER_CONTEXT, TRIPLE_CAP)

    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        for ctx, (total, distinct) in sorted(pair_ctx.items()):
            f.write(f"C\t{ctx}\t{total}\t{distinct}\n")
        for ctx, (total, distinct) in sorted(tri_ctx.items()):
            f.write(f"C\t{ctx}\t{total}\t{distinct}\n")
        for c, ctx, w in sorted(pair_rows, key=lambda r: (r[1], -r[0])):
            f.write(f"B\t{ctx}\t{w}\t{c}\n")
        for c, ctx, w in sorted(tri_rows, key=lambda r: (r[1], -r[0])):
            f.write(f"T\t{ctx}\t{w}\t{c}\n")

    lines = [
        f"sentences {len(texts)}, words {n_tok}",
        f"pairs kept {len(pair_rows)} in {len(pair_ctx)} contexts",
        f"triples kept {len(tri_rows)} in {len(tri_ctx)} contexts",
        f"left out as overused ({len(dropped)}): " + ", ".join(w for _, w in dropped),
    ]
    for ctx in (START, "and", "i", "a", "the", "to", "in", "with", "my", "<s> i", "i want",
                "going to", "how are", "i love", "what are", "see you"):
        row = pairs.get(ctx) if " " not in ctx else triples.get(ctx)
        if row:
            top = ", ".join(f"{w} {c}" for w, c in row.most_common(8))
            lines.append(f"after '{ctx}': {top}")
    report = "\n".join(lines)
    print(report)
    if STATS:
        with open(STATS, "w", encoding="utf-8") as f:
            f.write(report + "\n")


if __name__ == "__main__":
    main()
