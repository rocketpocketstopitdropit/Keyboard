package com.example.customkeyboard

import kotlin.math.abs
import kotlin.math.exp

/** One letter key on screen: its middle and size, in pixels. */
class LetterKey(val letter: Char, val cx: Float, val cy: Float, val w: Float, val h: Float)

/**
 * What the fingers' landing spots say about which letters were meant, for
 * the word being typed. The keyboard fills it in as letters are typed; the
 * autocorrector asks it how likely each letter is at each position, so a
 * finger that came down squarely on its key counts heavily against
 * "correcting" that letter, and one that landed between two keys hardly at
 * all. Plain Kotlin, no Android, so it can be tested off the phone.
 */
class TouchEvidence(private val model: TouchModel) {

    private val keys = arrayOfNulls<LetterKey>(26)

    /** The word whose touches these are, and each letter's landing spot and time (null = not tapped). */
    private var word = ""
    private val spots = ArrayList<FloatArray?>()
    private val times = ArrayList<Long>()

    fun setKeys(letterKeys: List<LetterKey>) {
        keys.fill(null)
        for (k in letterKeys) if (k.letter in 'a'..'z') keys[k.letter - 'a'] = k
    }

    fun hasKeys(): Boolean = keys.any { it != null }

    /** Forget the word (it was finished, or the cursor moved). */
    fun clear() {
        word = ""
        spots.clear()
        times.clear()
    }

    /** A letter was added to the word being typed; [x] is NaN when it wasn't a tap. */
    fun add(wordNow: String, x: Float, y: Float, downAt: Long) {
        if (wordNow.isEmpty() || wordNow.length > 48) {
            clear()
            return
        }
        if (wordNow.length != spots.size + 1) {
            // Out of step (part of the word came some other way): no touches for the earlier letters.
            clear()
            repeat(wordNow.length - 1) {
                spots.add(null)
                times.add(0L)
            }
        }
        spots.add(if (x.isNaN()) null else floatArrayOf(x, y))
        times.add(if (x.isNaN()) 0L else downAt)
        word = wordNow
    }

    /** The last letter was deleted. */
    fun removeLast(wordNow: String) {
        if (spots.isNotEmpty()) {
            spots.removeAt(spots.size - 1)
            times.removeAt(times.size - 1)
        }
        word = wordNow
        if (spots.size != wordNow.length) clear()
    }

    /** The touches for [w], if they are the word being typed. */
    private fun matches(w: String): Boolean = spots.size == w.length && w.equals(word, ignoreCase = true)

    fun touchAt(w: String, i: Int): FloatArray? = if (matches(w) && i in spots.indices) spots[i] else null

    /** Positions i where letters i and i+1 landed within [RACE_MS] of each other. */
    fun racePairs(w: String): IntArray {
        if (!matches(w)) return IntArray(0)
        var out = IntArray(0)
        for (i in 0 until times.size - 1) {
            val a = times[i]
            val b = times[i + 1]
            if (a > 0L && b > 0L && abs(b - a) <= RACE_MS) out += i
        }
        return out
    }

    /** True if the keys for [a] and [b] sit side by side or diagonally next to each other. */
    fun touching(a: Char, b: Char): Boolean {
        if (a !in 'a'..'z' || b !in 'a'..'z') return false
        val ka = keys[a - 'a'] ?: return false
        val kb = keys[b - 'a'] ?: return false
        val w = maxOf(ka.w, kb.w)
        val h = maxOf(ka.h, kb.h)
        if (w <= 0f || h <= 0f) return false
        return abs(ka.cx - kb.cx) <= 1.25f * w && abs(ka.cy - kb.cy) <= 1.25f * h
    }

    /**
     * Squared distance, in key sizes, from a touch to where this person's
     * finger usually lands for [letter]. NaN if the letter has no key.
     */
    fun distance2(touch: FloatArray, letter: Char): Double {
        if (letter !in 'a'..'z') return Double.NaN
        val k = keys[letter - 'a'] ?: return Double.NaN
        if (k.w <= 0f || k.h <= 0f) return Double.NaN
        val dx = (touch[0] - k.cx) / k.w - model.offsetX(letter)
        val dy = (touch[1] - k.cy) / k.h - model.offsetY(letter)
        return (dx * dx + dy * dy).toDouble()
    }

    /**
     * Going by where the finger for letter [i] of [w] landed, how many times
     * likelier it was aiming at [alt] than at the letter typed. NaN when
     * there's no touch to go by, or it was nowhere near [alt].
     */
    fun odds(w: String, i: Int, alt: Char): Double {
        val t = touchAt(w, i) ?: return Double.NaN
        val typed = distance2(t, w[i].lowercaseChar())
        val other = distance2(t, alt)
        if (typed.isNaN() || other.isNaN() || other > FAR_D2) return Double.NaN
        return exp(-(other - typed) / (2.0 * SIGMA * SIGMA))
    }

    /**
     * How a stray extra tap compares with a real one at letter [i]: a touch
     * well away from where the finger usually lands for that letter is
     * likelier to be a stray. NaN with no touch.
     */
    fun strayOdds(w: String, i: Int): Double {
        val t = touchAt(w, i) ?: return Double.NaN
        val d2 = distance2(t, w[i].lowercaseChar())
        if (d2.isNaN()) return Double.NaN
        return if (d2 >= OFF_KEY_D2) STRAY_ODDS_OFF_KEY else STRAY_ODDS_ON_KEY
    }

    /**
     * The autocorrector's costs for [w], built from its touches: for each
     * typed letter, what reading it as each other letter costs, what treating
     * it as a stray tap costs, and which neighbouring pairs may have landed
     * in the wrong order. Null when there are no touches for this word.
     */
    fun costs(w: String): Autocorrector.TouchCosts? {
        if (!matches(w) || spots.all { it == null }) return null
        val lower = w.lowercase()
        val n = lower.length
        val sub = arrayOfNulls<DoubleArray>(n)
        val extra = DoubleArray(n)
        val race = BooleanArray(n)
        val key = StringBuilder(lower).append('|')
        for (i in 0 until n) {
            val c = lower[i]
            val doubled = i > 0 && lower[i - 1] == c
            val t = spots[i]
            val d2t = if (t == null) Double.NaN else distance2(t, c)
            if (t == null || d2t.isNaN()) {
                extra[i] = if (doubled) Autocorrector.DOUBLED_EXTRA else 1.0
                key.append("-;")
                continue
            }
            val row = DoubleArray(26)
            for (k in 0 until 26) {
                val alt = 'a' + k
                if (alt == c) continue
                val d2 = distance2(t, alt)
                var cost = when {
                    d2.isNaN() || d2 > FAR_D2 -> 1.0
                    else -> (SUB_BASE + SUB_SCALE * (d2 - d2t)).coerceIn(SUB_MIN, 1.0)
                }
                // Thumbs do sometimes land a whole key off ("ofer" for "over"), so a key
                // that touches the one typed is never ruled out, however clean the tap.
                if (cost > NEIGHBOUR_MAX && touching(c, alt)) cost = NEIGHBOUR_MAX
                row[k] = cost
            }
            sub[i] = row
            extra[i] = when {
                doubled -> Autocorrector.DOUBLED_EXTRA
                d2t >= OFF_KEY_D2 -> EXTRA_OFF_KEY
                else -> 1.0
            }
            key.append(Math.round(t[0])).append(',').append(Math.round(t[1])).append(';')
        }
        for (i in racePairs(w)) race[i + 1] = true
        for (i in 1 until n) if (race[i]) key.append('r').append(i)
        return Autocorrector.TouchCosts(sub, extra, race, key.toString())
    }

    companion object {
        /** Spread of a finger around its usual landing spot, in key sizes (the same as for picking keys). */
        const val SIGMA = 0.4

        /** Beyond 1.5 keys from a letter's spot, a touch says nothing useful for that letter. */
        const val FAR_D2 = 2.25

        /** Two letters landing this close together: the order they arrived in is down to chance. */
        const val RACE_MS = 70L

        /** Reading a tapped letter as another: base cost, plus this per unit of squared distance. */
        const val SUB_BASE = 0.15
        const val SUB_SCALE = 0.3
        const val SUB_MIN = 0.05

        /** The most reading a tap as a key touching it can cost, however clean the tap. */
        const val NEIGHBOUR_MAX = 0.6

        /** A touch this far (squared, in key sizes) from the letter's usual spot is off its key. */
        const val OFF_KEY_D2 = 0.3

        /** Cost of treating a tap that landed off its key as a stray extra tap. */
        const val EXTRA_OFF_KEY = 0.7

        const val STRAY_ODDS_ON_KEY = 1.0 / 40.0
        const val STRAY_ODDS_OFF_KEY = 1.0 / 10.0
    }
}
