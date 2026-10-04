package com.example.customkeyboard

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The person's words as a plain text file they can read, edit and import
 * again: their added words, and everything the prediction model has learned
 * from their typing.
 *
 *   [added]            one word per line
 *   [words]            word count
 *   [pairs]            first next count      ("<s>" first = start of a sentence)
 *
 * Words and numbers are separated by spaces or tabs; the count is optional
 * (1 if left out); a ";" or "," at the end of a line is ignored; anything
 * after "#" is a note. Lines before any [section] count as [added], so a
 * bare list of words imports as added words.
 *
 * Importing replaces each section the file contains and leaves the others
 * alone, so deleting a line forgets that word.
 */
object WordListFile {

    class ImportResult(val added: Int?, val words: Int?, val pairs: Int?, val skipped: List<String>)

    private fun fmt(c: Float): String {
        val r = Math.round(c * 10f) / 10f
        return if (r == r.toLong().toFloat()) r.toLong().toString() else String.format(Locale.US, "%.1f", r)
    }

    fun export(): String {
        val sb = StringBuilder()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        sb.append("# Custom Keyboard words, exported $stamp\n")
        sb.append("#\n")
        sb.append("# One entry per line. Anything after # is a note and is ignored.\n")
        sb.append("# [added]  your own words, one per line.\n")
        sb.append("# [words]  words learned from your typing:   word  count\n")
        sb.append("# [pairs]  which word came after which:     first  next  count\n")
        sb.append("#          <s> as the first word means the start of a sentence.\n")
        sb.append("# Use spaces or tabs between parts. The count is optional (1 if left out).\n")
        sb.append("# A ; or , at the end of a line is fine and is ignored.\n")
        sb.append("# Importing replaces each section that is in the file; leave a section's\n")
        sb.append("# [header] out to keep what the phone already has for it.\n")
        sb.append("# Delete a line to make the keyboard forget it.\n")

        sb.append("\n[added]\n")
        for (w in WordPredictor.addedWords()) sb.append(w).append('\n')

        sb.append("\n[words]\n")
        val words = WordPredictor.learnedWordCounts().entries
            .sortedWith(compareByDescending<Map.Entry<String, Float>> { it.value }.thenBy { it.key })
        for ((w, c) in words) sb.append(w).append(' ').append(fmt(c)).append('\n')

        sb.append("\n[pairs]\n")
        val pairs = ArrayList<Triple<String, String, Float>>()
        for ((p, row) in WordPredictor.learnedPairCounts()) for ((w, c) in row) pairs.add(Triple(p, w, c))
        pairs.sortWith(compareByDescending<Triple<String, String, Float>> { it.third }
            .thenBy { it.first }.thenBy { it.second })
        for ((p, w, c) in pairs) sb.append(p).append(' ').append(w).append(' ').append(fmt(c)).append('\n')
        return sb.toString()
    }

    fun import(text: String): ImportResult {
        val addedLines = ArrayList<Pair<Int, String>>()
        val wordLines = ArrayList<Pair<Int, List<String>>>()
        val pairLines = ArrayList<Pair<Int, List<String>>>()
        var hasAdded = false
        var hasWords = false
        var hasPairs = false
        val skipped = ArrayList<String>()

        var section = "added"
        text.removePrefix("﻿").lines().forEachIndexed { i, raw ->
            val lineNo = i + 1
            val line = raw.substringBefore('#').trim().trimEnd(';', ',').trim()
            if (line.isEmpty()) return@forEachIndexed
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length - 1).trim().lowercase()
                when (section) {
                    "added" -> hasAdded = true
                    "words" -> hasWords = true
                    "pairs" -> hasPairs = true
                    else -> skipped.add("line $lineNo: unknown section $line")
                }
                return@forEachIndexed
            }
            val parts = line.split(Regex("[\\s;,]+")).filter { it.isNotEmpty() }
            when (section) {
                "added" -> { hasAdded = true; addedLines.add(lineNo to line) }
                "words" -> wordLines.add(lineNo to parts)
                "pairs" -> pairLines.add(lineNo to parts)
            }
        }
        if (!hasAdded && !hasWords && !hasPairs) throw IllegalArgumentException("nothing to import")

        // Added words first: what counts as a real word below depends on them.
        val oldAdded = WordPredictor.addedWords().toSet()
        val newAdded = LinkedHashSet<String>()
        for ((lineNo, w0) in addedLines) {
            val w = WordPredictor.normalize(w0)
            when {
                w.contains(' ') || !WordPredictor.isPlainWord(w) ->
                    skipped.add("line $lineNo: \"$w0\" isn't a single plain word")
                WordPredictor.canBeAdded(w) -> newAdded.add(w)
                // Already a dictionary word; nothing to add.
                else -> {}
            }
        }
        fun known(w: String): Boolean = when {
            w == WordPredictor.START -> true
            hasAdded && w in newAdded -> true
            hasAdded && w in oldAdded -> false
            else -> WordPredictor.isKnown(w)
        }
        fun count(s: String?, lineNo: Int): Float? {
            if (s == null) return 1f
            val c = s.toFloatOrNull()
            if (c == null || c <= 0f) {
                skipped.add("line $lineNo: \"$s\" isn't a count above 0")
                return null
            }
            return c.coerceAtMost(1_000_000f)
        }

        val words = HashMap<String, Float>()
        for ((lineNo, parts) in wordLines) {
            if (parts.size > 2) { skipped.add("line $lineNo: expected  word count"); continue }
            val w = WordPredictor.normalize(parts[0])
            if (!known(w)) { skipped.add("line $lineNo: \"$w\" isn't a known or added word"); continue }
            val c = count(parts.getOrNull(1), lineNo) ?: continue
            words[w] = (words[w] ?: 0f) + c
        }

        val pairs = HashMap<String, HashMap<String, Float>>()
        var pairCount = 0
        for ((lineNo, parts) in pairLines) {
            if (parts.size < 2 || parts.size > 3) { skipped.add("line $lineNo: expected  first next count"); continue }
            val p = WordPredictor.normalize(parts[0])
            val w = WordPredictor.normalize(parts[1])
            if (!known(p)) { skipped.add("line $lineNo: \"$p\" isn't a known or added word"); continue }
            if (w == WordPredictor.START || !known(w)) { skipped.add("line $lineNo: \"$w\" isn't a known or added word"); continue }
            val c = count(parts.getOrNull(2), lineNo) ?: continue
            val row = pairs.getOrPut(p) { HashMap() }
            if (!row.containsKey(w)) pairCount++
            row[w] = (row[w] ?: 0f) + c
        }

        WordPredictor.replaceUserData(
            added = if (hasAdded) newAdded else null,
            words = if (hasWords) words else null,
            pairs = if (hasPairs) pairs else null
        )
        return ImportResult(
            added = if (hasAdded) newAdded.size else null,
            words = if (hasWords) words.size else null,
            pairs = if (hasPairs) pairCount else null,
            skipped = skipped
        )
    }
}
