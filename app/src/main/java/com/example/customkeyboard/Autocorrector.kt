package com.example.customkeyboard

import kotlin.math.abs

object Autocorrector {

    private val ROWS = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

    private fun keyPosition(c: Char): Pair<Int, Int>? {
        val lower = c.lowercaseChar()
        ROWS.forEachIndexed { row, chars ->
            val col = chars.indexOf(lower)
            if (col >= 0) return row to col
        }
        return null
    }

    private fun substitutionCost(typed: Char, intended: Char): Double {
        if (typed == intended) return 0.0
        val a = keyPosition(typed)
        val b = keyPosition(intended)
        if (a != null && b != null) {
            val dist = abs(a.first - b.first) + abs(a.second - b.second)
            if (dist <= 1) return 0.3
        }
        return 1.0
    }

    private fun typoDistance(a: String, b: String): Double {
        val dp = Array(a.length + 1) { DoubleArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i.toDouble()
        for (j in 0..b.length) dp[0][j] = j.toDouble()
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val sub = dp[i - 1][j - 1] + substitutionCost(a[i - 1], b[j - 1])
                val del = dp[i - 1][j] + 1.0
                val ins = dp[i][j - 1] + 1.0
                dp[i][j] = minOf(sub, del, ins)
            }
        }
        return dp[a.length][b.length]
    }

    private const val FREQUENCY_WEIGHT = 0.35

    fun correctionFor(typedWord: String): String? {
        val lower = typedWord.lowercase()
        if (lower.length < 2) return null
        if (WordPredictor.WORDS.contains(lower)) return null

        var best: String? = null
        var bestScore = Double.MAX_VALUE
        var bestRawDist = Double.MAX_VALUE
        for (candidate in WordPredictor.WORDS) {
            if (abs(candidate.length - lower.length) > 2) continue
            val dist = typoDistance(lower, candidate)
            val score = dist - FREQUENCY_WEIGHT * WordPredictor.frequencyOf(candidate)
            if (score < bestScore) {
                bestScore = score
                bestRawDist = dist
                best = candidate
            }
        }
        val threshold = 1.2
        return if (best != null && bestRawDist <= threshold && best != lower) best else null
    }
}
