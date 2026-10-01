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

    /** Longest edit distance that still counts as "clearly a typo". */
    private const val MAX_DISTANCE = 1.2

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
            for (j in 1..m) {
                var best = prev1[j - 1] + substitutionCost(a[i - 1], b[j - 1])
                val del = prev1[j] + 1.0
                if (del < best) best = del
                val ins = cur[j - 1] + 1.0
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

        if (WordPredictor.isKnown(lower)) return null

        // Possessives and "'s" contractions ("john's") are almost never typos.
        if (lower.endsWith("'s")) return null

        CONTRACTIONS[lower]?.let { return it }

        val limit = if (lower.length <= 2) SHORT_WORD_MAX_DISTANCE else MAX_DISTANCE
        val prior = WordPredictor.priorScorer(previousWord)
        val ws = Workspace(lower.length + 3)

        var best: String? = null
        var bestScore = Double.MAX_VALUE
        // Each insert/delete costs 1.0, so a length gap of 2 can never fit under the
        // limit: only words one letter shorter, the same length, or one longer are tried.
        for (length in (lower.length - 1)..(lower.length + 1)) {
            for (candidate in WordPredictor.wordsOfLength(length)) {
                val d = typoDistance(lower, candidate, limit, ws)
                if (d > limit) continue
                val score = d - PRIOR_WEIGHT * prior(candidate)
                if (score < bestScore) {
                    bestScore = score
                    best = candidate
                }
            }
        }
        return if (best != null && best != lower) best else null
    }
}
