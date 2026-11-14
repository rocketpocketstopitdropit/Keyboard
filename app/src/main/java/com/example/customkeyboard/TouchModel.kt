package com.example.customkeyboard

/**
 * Where this person's finger really lands for each letter key.
 *
 * Nobody hits the middle of a key. Measured on the phone, touches on the top
 * row land well below the middle of the key, and touches on the outer
 * columns land toward the middle of the keyboard, often in the gap or on the
 * next key over. So each letter keeps its own "landing spot": an offset from
 * the middle of its key, in key widths and key heights (right and down are
 * positive). The keyboard picks the key whose landing spot is nearest the
 * finger, and the spots keep adjusting to how the person types.
 *
 * A letter that has never been learned uses a starting guess ([seed]) from
 * those measurements. Plain Kotlin, no Android, so it can be tested off the
 * phone.
 */
class TouchModel {

    private val dx = FloatArray(26)
    private val dy = FloatArray(26)
    private val count = IntArray(26)

    /** True when something was learned since the last [save]. */
    var dirty = false
        private set

    private fun index(letter: Char): Int = if (letter in 'a'..'z') letter - 'a' else -1

    fun offsetX(letter: Char): Float = index(letter).let { if (it < 0) 0f else dx[it] }
    fun offsetY(letter: Char): Float = index(letter).let { if (it < 0) 0f else dy[it] }

    /** Sets the starting guess for a letter nothing has been learned about yet. */
    fun seed(letter: Char, seedDx: Float, seedDy: Float) {
        val i = index(letter)
        if (i < 0 || count[i] > 0) return
        dx[i] = seedDx
        dy[i] = seedDy
    }

    /**
     * A finger meant for [letter] landed at this offset from the middle of its
     * key. Early touches move the landing spot quickly, later ones slowly, so
     * it settles but can still follow a change of grip.
     */
    fun learn(letter: Char, sampleDx: Float, sampleDy: Float) {
        val i = index(letter)
        if (i < 0) return
        // Further than this from the key isn't a near miss; it's some other mistake.
        if (sampleDx < -MAX_SAMPLE || sampleDx > MAX_SAMPLE || sampleDy < -MAX_SAMPLE || sampleDy > MAX_SAMPLE) return
        val rate = maxOf(MIN_RATE, 1f / (count[i] + SEED_WEIGHT))
        dx[i] = (dx[i] + (sampleDx - dx[i]) * rate).coerceIn(-MAX_OFFSET, MAX_OFFSET)
        dy[i] = (dy[i] + (sampleDy - dy[i]) * rate).coerceIn(-MAX_OFFSET, MAX_OFFSET)
        if (count[i] < Int.MAX_VALUE) count[i]++
        dirty = true
    }

    /** Learned letters as text, for storage: "a:0.120,0.300,15;b:...". */
    fun save(): String {
        val sb = StringBuilder()
        for (i in 0 until 26) {
            if (count[i] == 0) continue
            if (sb.isNotEmpty()) sb.append(';')
            sb.append('a' + i).append(':')
                .append(Math.round(dx[i] * 1000f) / 1000f).append(',')
                .append(Math.round(dy[i] * 1000f) / 1000f).append(',')
                .append(count[i])
        }
        dirty = false
        return sb.toString()
    }

    /** Forgets everything, then reads back what [save] wrote. Damaged entries are skipped. */
    fun load(text: String?) {
        dx.fill(0f)
        dy.fill(0f)
        count.fill(0)
        dirty = false
        if (text.isNullOrEmpty()) return
        for (entry in text.split(';')) {
            val letter = entry.substringBefore(':', "")
            val parts = entry.substringAfter(':', "").split(',')
            if (letter.length != 1 || parts.size != 3) continue
            val i = index(letter[0])
            val x = parts[0].toFloatOrNull()
            val y = parts[1].toFloatOrNull()
            val n = parts[2].toIntOrNull()
            if (i < 0 || x == null || y == null || n == null || n <= 0) continue
            dx[i] = x.coerceIn(-MAX_OFFSET, MAX_OFFSET)
            dy[i] = y.coerceIn(-MAX_OFFSET, MAX_OFFSET)
            count[i] = n
        }
    }

    /** One line for the typing monitor: each letter's landing spot in percent, and how many touches it's from. */
    fun describe(): String {
        val sb = StringBuilder()
        for (i in 0 until 26) {
            if (dx[i] == 0f && dy[i] == 0f && count[i] == 0) continue
            sb.append(' ').append('a' + i).append(Math.round(dx[i] * 100f)).append(',')
                .append(Math.round(dy[i] * 100f)).append('/').append(count[i])
        }
        return sb.toString()
    }

    companion object {
        private const val SEED_WEIGHT = 4
        private const val MIN_RATE = 0.06f
        private const val MAX_OFFSET = 1.0f
        private const val MAX_SAMPLE = 1.2f

        /**
         * Starting guess on a narrow (phone-width) keyboard, from the typing
         * monitor: the further a key is from the middle column, the further
         * toward the middle the finger lands; and the higher the row, the
         * lower in the key it lands. A little under what was measured, so it
         * helps from the first tap and learning does the rest.
         *
         * [columnsFromMiddle] is the key's distance from the middle of the
         * keyboard in key widths (negative = left); [rowFromTop] counts
         * letter rows from 0.
         */
        fun seedDx(columnsFromMiddle: Float): Float = (-0.07f * columnsFromMiddle).coerceIn(-0.28f, 0.28f)

        fun seedDy(rowFromTop: Int, rows: Int): Float = when {
            rows != 3 -> 0.08f
            rowFromTop == 0 -> 0.20f
            rowFromTop == 1 -> 0.06f
            else -> 0f
        }
    }
}
