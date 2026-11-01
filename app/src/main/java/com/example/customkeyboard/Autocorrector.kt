package com.example.customkeyboard

import kotlin.math.abs

/**
 * Fixes a mistyped word when the space bar is pressed.
 *
 * A candidate's cost is a keyboard-aware edit distance (a slip onto a
 * neighbouring key is cheap, a swapped pair of letters is cheap, anything
 * else costs a full edit), minus a bonus for how likely the word is given
 * the word before it. Words the person has used repeatedly are never
 * "corrected" away.
 */
object Autocorrector {

    private val ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

    private val POSITIONS: Map<Char, Pair<Int, Int>> = HashMap<Char, Pair<Int, Int>>().also { map ->
        ROWS.forEachIndexed { row, chars ->
            chars.forEachIndexed { col, ch -> map[ch] = row to col }
        }
    }

    private const val NEIGHBOUR_COST = 0.3
    private const val TRANSPOSE_COST = 0.6
    private const val PRIOR_WEIGHT = 0.6

    /** A rare word is only fixed toward a word at least this much more common (Zipf, ~300x). */
    private const val RARE_GAP = 2.5
    /** ...and the fix must itself be an everyday word. */
    private const val RARE_MIN_TARGET = 4.0
    /** Below this, two neighbour slips are allowed too. */
    private const val VERY_RARE_ZIPF = 2.5

    /** Longest edit distance that still counts as "clearly a typo" (3-4 letter words). */
    private const val MAX_DISTANCE = 1.2
    /** 5-6 letters: about one and a half slips ("cuatoem" -> "custom"). */
    private const val MEDIUM_WORD_MAX_DISTANCE = 1.6
    /** 7+ letters: up to two slips ("postitoon" -> "position", "chrslr" -> "chrysler"). */
    private const val LONG_WORD_MAX_DISTANCE = 2.2
    /** Beyond this the first letter has to match, so long-shot fixes stay believable. */
    private const val SAME_START_ABOVE = 1.2

    private const val VOWEL_MISSING_COST = 0.6
    private const val DOUBLED_COST = 0.5
    private const val VOWELS = "aeiouy"

    /** Two-letter words are too ambiguous for anything but a single slip. */
    private const val SHORT_WORD_MAX_DISTANCE = 0.35

    /** Apostrophe-less spellings that are safe to restore (not "its", "ill", "wont"...). */
    private val CONTRACTIONS: Map<String, String> = mapOf(
        "im" to "I'm", "ive" to "I've", "dont" to "don't", "cant" to "can't",
        "didnt" to "didn't", "doesnt" to "doesn't", "isnt" to "isn't", "wasnt" to "wasn't",
        "couldnt" to "couldn't", "wouldnt" to "wouldn't", "shouldnt" to "shouldn't",
        "havent" to "haven't", "hasnt" to "hasn't", "arent" to "aren't", "werent" to "weren't",
        "youre" to "you're", "theyre" to "they're", "thats" to "that's", "whats" to "what's",
        "theres" to "there's"
    )

    private val I_CONTRACTIONS = setOf("i'm", "i'll", "i've", "i'd")

    private class Workspace(size: Int) {
        val a = DoubleArray(size)
        val b = DoubleArray(size)
        val c = DoubleArray(size)
    }

    private fun substitutionCost(typed: Char, intended: Char): Double {
        if (typed == intended) return 0.0
        val a = POSITIONS[typed]
        val b = POSITIONS[intended]
        if (a != null && b != null) {
            val dist = abs(a.first - b.first) + abs(a.second - b.second)
            if (dist <= 1) return NEIGHBOUR_COST
        }
        return 1.0
    }

    /**
     * Cost of a letter the person skipped. Fast thumbs mostly drop vowels
     * ("chrslr" for "chrysler") and one half of a double letter ("leter"),
     * so those cost less than any other missing letter.
     */
    private fun missingCost(word: String, at: Int): Double {
        val c = word[at]
        if (at > 0 && word[at - 1] == c) return DOUBLED_COST
        return if (c in VOWELS) VOWEL_MISSING_COST else 1.0
    }

    /** How far off a typed word may be and still count as a typo: longer words can take more slips. */
    private fun limitFor(length: Int): Double = when {
        length <= 2 -> SHORT_WORD_MAX_DISTANCE
        length <= 4 -> MAX_DISTANCE
        length <= 6 -> MEDIUM_WORD_MAX_DISTANCE
        else -> LONG_WORD_MAX_DISTANCE
    }

    /**
     * Keyboard-aware Damerau-style edit distance. Gives up early (returning
     * a value above [limit]) once no alignment can possibly come in under it.
     */
    private fun typoDistance(a: String, b: String, limit: Double, ws: Workspace): Double {
        val n = a.length
        val m = b.length
        var prev2 = ws.a
        var prev1 = ws.b
        var cur = ws.c
        for (j in 0..m) prev1[j] = j.toDouble()
        var lastRowMin = 0.0

        for (i in 1..n) {
            cur[0] = i.toDouble()
            var rowMin = cur[0]
            // A letter typed twice by accident ("lookk") is a cheap extra letter.
            val extraCost = if (i > 1 && a[i - 1] == a[i - 2]) DOUBLED_COST else 1.0
            for (j in 1..m) {
                var best = prev1[j - 1] + substitutionCost(a[i - 1], b[j - 1])
                val del = prev1[j] + extraCost
                if (del < best) best = del
                val ins = cur[j - 1] + missingCost(b, j - 1)
                if (ins < best) best = ins
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    val swap = prev2[j - 2] + TRANSPOSE_COST
                    if (swap < best) best = swap
                }
                cur[j] = best
                if (best < rowMin) rowMin = best
            }
            // A swap reaches back two rows, so both of the last two must be out of range.
            if (rowMin > limit && lastRowMin > limit) return rowMin
            lastRowMin = rowMin
            val tmp = prev2
            prev2 = prev1
            prev1 = cur
            cur = tmp
        }
        return prev1[m]
    }

    /** True if [b] is [a] with one pair of neighbouring letters swapped ("teh" / "the"). */
    private fun isSwap(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var first = -1
        var count = 0
        for (i in a.indices) {
            if (a[i] != b[i]) {
                if (count == 0) first = i
                count++
                if (count > 2) return false
            }
        }
        return count == 2 && first + 1 < a.length && a[first] != b[first] && a[first + 1] != b[first + 1] &&
                a[first] == b[first + 1] && a[first + 1] == b[first]
    }

    /**
     * A real-but-rare word ([zipf] = how common it is) is replaced only by a
     * word of the same length that is hundreds of times more common and
     * reached by a single slip onto a neighbouring key or a swapped pair. The
     * very rarest also allow two neighbour slips ("abf" -> "and").
     */
    private fun rareWordCorrection(lower: String, zipf: Double, previousWord: String): String? {
        if (lower.length < 3) return null
        val minZipf = maxOf(zipf + RARE_GAP, RARE_MIN_TARGET)
        val prior = WordPredictor.priorScorer(previousWord)
        val ws = Workspace(lower.length + 3)
        var best: String? = null
        var bestScore = Double.MAX_VALUE
        for (candidate in WordPredictor.wordsOfLength(lower.length)) {
            val cz = WordPredictor.zipfOf(candidate) ?: continue
            if (cz < minZipf) continue
            val d = typoDistance(lower, candidate, TRANSPOSE_COST + 0.05, ws)
            val ok = d <= NEIGHBOUR_COST + 0.05 ||
                    (d <= TRANSPOSE_COST + 0.05 && (zipf < VERY_RARE_ZIPF || isSwap(lower, candidate)))
            if (!ok) continue
            val score = d - PRIOR_WEIGHT * prior(candidate)
            if (score < bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return best
    }

    /**
     * The correction for [typedWord] (typed after [previousWord]), or null
     * to leave it alone.
     */
    fun correctionFor(typedWord: String, previousWord: String = ""): String? {
        // A lone lowercase "i" is always the pronoun.
        if (typedWord == "i") return "I"

        val lower = WordPredictor.normalize(typedWord)
        if (lower.length < 2) return null

        // "i'm" -> "I'm" (checked before the known-word test, since "i'm" is a known word)
        if (typedWord[0] == 'i' && lower in I_CONTRACTIONS) return "I" + typedWord.substring(1)

        if (WordPredictor.isKnown(lower)) {
            // Rare entries in the big word list include everyday slips ("rhe",
            // "abd", "yoy"), so a rare word still gets fixed when a far more
            // common word is one slip away.
            val zipf = WordPredictor.rareZipfOf(lower) ?: return null
            return rareWordCorrection(lower, zipf, previousWord)
        }

        // Possessives and "'s" contractions ("john's") are almost never typos.
        if (lower.endsWith("'s")) return null

        CONTRACTIONS[lower]?.let { return it }

        val best = nearWords(lower, previousWord).firstOrNull() ?: return null
        return if (best != lower) best else null
    }

    private var nearTyped: String? = null
    private var nearPrev: String? = null
    private var nearStamp = -1
    private var nearResult: List<String> = emptyList()

    /**
     * Real words close to an unknown typed word, best first (at most
     * [NEAR_COUNT]). The first one is the autocorrection; the rest can fill
     * the suggestion bar when nothing starts with what was typed.
     */
    fun nearMatches(typedWord: String, previousWord: String = ""): List<String> {
        val lower = WordPredictor.normalize(typedWord)
        if (lower.length < 2 || WordPredictor.isKnown(lower)) return emptyList()
        return nearWords(lower, previousWord)
    }

    private fun nearWords(lower: String, previousWord: String): List<String> {
        val stamp = WordPredictor.vocabularyStamp()
        if (nearTyped == lower && nearPrev == previousWord && nearStamp == stamp) return nearResult
        val limit = limitFor(lower.length)
        val prior = WordPredictor.priorScorer(previousWord)
        val ws = Workspace(lower.length + 4)
        // The cheapest a missing or extra letter can be is DOUBLED_COST, which bounds the length gap.
        val gap = minOf(2, (limit / DOUBLED_COST).toInt())
        val found = ArrayList<Pair<String, Double>>()
        for (length in (lower.length - gap)..(lower.length + gap)) {
            if (length < 1) continue
            val farLength = abs(length - lower.length) >= 2
            for (candidate in WordPredictor.wordsOfLength(length)) {
                if (farLength && candidate[0] != lower[0]) continue
                val d = typoDistance(lower, candidate, limit, ws)
                if (d > limit) continue
                if (d > SAME_START_ABOVE && candidate[0] != lower[0]) continue
                found.add(candidate to d - PRIOR_WEIGHT * prior(candidate))
            }
        }
        found.sortBy { it.second }
        val result = found.take(NEAR_COUNT).map { it.first }
        nearTyped = lower
        nearPrev = previousWord
        nearStamp = stamp
        nearResult = result
        return result
    }

    private const val NEAR_COUNT = 4

    // ---------- the suggestion bar (shared by the keyboard and the lookup in settings) ----------

    /** The three slots of the suggestion bar. [best] is what a flick up on space accepts. */
    class Bar(val left: String?, val centre: String?, val right: String?, val best: String?, val words: List<String>)

    /**
     * What the suggestion bar shows for [typed] after [previousWord]: the
     * autocorrection, then words that start with what was typed, then the
     * closest real words. If the typed word isn't a real word (or would be
     * corrected) it also appears on the left in quotes, to keep it as typed.
     * [shape] matches each word to the typed capitals.
     */
    fun bar(typed: String, previousWord: String, shape: (String) -> String = { it }): Bar {
        val words = ArrayList<String>()
        val correction = correctionFor(typed, previousWord)
        correction?.let { words.add(shape(it)) }
        fun offer(w: String) {
            val shown = shape(w)
            if (words.none { it.equals(shown, ignoreCase = true) }) words.add(shown)
        }
        for (w in WordPredictor.suggestions(previousWord, typed, 4)) offer(w)
        // Nothing (or little) starts with a mistyped word, so offer the closest real words too.
        if (words.size < 3) {
            for (w in nearMatches(typed, previousWord)) {
                offer(w)
                if (words.size >= 3) break
            }
        }
        val best = words.getOrNull(0)
        val literal = if (typed.length >= 2 && typed.last() != '\'' && typed.last() != '\u2019' &&
            (!WordPredictor.isKnown(typed) || correction != null)
        ) "\"" + typed + "\"" else null
        return when {
            literal != null && best != null -> Bar(literal, best, words.getOrNull(1), best, words)
            literal != null -> Bar(null, literal, null, null, words)
            else -> Bar(words.getOrNull(1), best ?: typed, words.getOrNull(2), best, words)
        }
    }

    // ---------- explaining a correction ----------

    /**
     * In plain words, how [typed] differs from [target]: the cheapest set of
     * slips, the same way the autocorrector counts them, e.g.
     * "r instead of e (next key)", "missing y", "extra o".
     */
    fun explain(typed: String, target: String): Pair<Double, List<String>> {
        val a = WordPredictor.normalize(typed)
        val b = WordPredictor.normalize(target)
        val n = a.length
        val m = b.length
        val d = Array(n + 1) { DoubleArray(m + 1) }
        for (j in 0..m) d[0][j] = j.toDouble()
        for (i in 1..n) {
            d[i][0] = i.toDouble()
            val extraCost = if (i > 1 && a[i - 1] == a[i - 2]) DOUBLED_COST else 1.0
            for (j in 1..m) {
                var best = d[i - 1][j - 1] + substitutionCost(a[i - 1], b[j - 1])
                best = minOf(best, d[i - 1][j] + extraCost, d[i][j - 1] + missingCost(b, j - 1))
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    best = minOf(best, d[i - 2][j - 2] + TRANSPOSE_COST)
                }
                d[i][j] = best
            }
        }
        val steps = ArrayList<String>()
        var i = n
        var j = m
        fun near(x: Double, y: Double) = abs(x - y) < 1e-9
        while (i > 0 || j > 0) {
            val here = d[i][j]
            if (i > 0 && j > 0 && near(here, d[i - 1][j - 1] + substitutionCost(a[i - 1], b[j - 1]))) {
                val c = substitutionCost(a[i - 1], b[j - 1])
                if (c > 0.0) {
                    steps.add("${a[i - 1]} instead of ${b[j - 1]}" + if (c < 1.0) " (next key)" else "")
                }
                i--; j--
            } else if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1] &&
                near(here, d[i - 2][j - 2] + TRANSPOSE_COST)
            ) {
                steps.add("${a[i - 2]}${a[i - 1]} swapped")
                i -= 2; j -= 2
            } else if (j > 0 && near(here, d[i][j - 1] + missingCost(b, j - 1))) {
                steps.add("missing ${b[j - 1]}")
                j--
            } else {
                steps.add("extra ${a[i - 1]}")
                i--
            }
        }
        steps.reverse()
        return d[n][m] to steps
    }

    /** The most a word of this length may be off and still be autocorrected. */
    fun allowedDistance(length: Int): Double = limitFor(length)
}
