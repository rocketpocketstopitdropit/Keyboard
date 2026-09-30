package com.example.customkeyboard

import android.content.Context
import java.io.File
import java.util.concurrent.Executors

/**
 * What the keyboard has learned from the person's own typing: how often
 * they use each word, and which word tends to follow which. Everything
 * stays on the device in a small tab-separated file in the app's private
 * storage. Counts decay over time so old habits fade, and the tables are
 * capped so the file can't grow without bound.
 *
 * Only ever touched from the keyboard's main thread; the file write is
 * handed a finished string and runs on a background thread.
 */
class UserLanguageModel {

    private val unigrams = HashMap<String, Float>()
    private val bigrams = HashMap<String, HashMap<String, Float>>()
    private var unigramTotal = 0f
    private var eventsSinceMaintenance = 0
    private var eventsSinceSave = 0

    /** Bumped whenever the set of "known" words changes, so caches can rebuild. */
    var vocabularyVersion = 0
        private set

    private var file: File? = null

    fun load(context: Context) {
        val f = File(context.filesDir, FILE_NAME)
        file = f
        if (!f.exists()) return
        try {
            f.forEachLine { line ->
                val parts = line.split('\t')
                when {
                    parts.size == 3 && parts[0] == "U" -> {
                        val c = parts[2].toFloatOrNull() ?: return@forEachLine
                        unigrams[parts[1]] = c
                    }
                    parts.size == 4 && parts[0] == "B" -> {
                        val c = parts[3].toFloatOrNull() ?: return@forEachLine
                        bigrams.getOrPut(parts[1]) { HashMap() }[parts[2]] = c
                    }
                }
            }
        } catch (e: Exception) {
            // A damaged file just means starting fresh.
            unigrams.clear()
            bigrams.clear()
        }
        unigramTotal = unigrams.values.sum()
        vocabularyVersion++
    }

    fun unigramCount(word: String): Float = unigrams[word] ?: 0f
    fun unigramTotal(): Float = unigramTotal
    fun bigramRow(previous: String): Map<String, Float>? = bigrams[previous]

    /** Words used often enough to be trusted as real (not one-off typos). */
    fun isKnown(word: String): Boolean = unigramCount(word) >= KNOWN_THRESHOLD

    fun knownWords(): List<String> =
        unigrams.entries.filter { it.value >= KNOWN_THRESHOLD }.map { it.key }

    fun learn(previous: String, word: String, weight: Float) {
        val wasKnown = isKnown(word)
        unigrams[word] = (unigrams[word] ?: 0f) + weight
        unigramTotal += weight
        if (previous.isNotEmpty()) {
            val row = bigrams.getOrPut(previous) { HashMap() }
            row[word] = (row[word] ?: 0f) + weight
        }
        if (isKnown(word) != wasKnown) vocabularyVersion++
        afterChange()
    }

    /** Reverse a learn() — used when the person undoes an autocorrection. */
    fun unlearn(previous: String, word: String, weight: Float) {
        val wasKnown = isKnown(word)
        val c = unigrams[word]
        if (c != null) {
            val left = c - weight
            if (left <= 0f) unigrams.remove(word) else unigrams[word] = left
            unigramTotal = (unigramTotal - minOf(c, weight)).coerceAtLeast(0f)
        }
        val row = bigrams[previous]
        if (row != null) {
            val b = row[word]
            if (b != null) {
                val left = b - weight
                if (left <= 0f) row.remove(word) else row[word] = left
            }
            if (row.isEmpty()) bigrams.remove(previous)
        }
        if (isKnown(word) != wasKnown) vocabularyVersion++
        eventsSinceSave++
    }

    fun clear(context: Context) {
        unigrams.clear()
        bigrams.clear()
        unigramTotal = 0f
        vocabularyVersion++
        val f = file ?: File(context.filesDir, FILE_NAME)
        writer.execute { try { f.delete() } catch (e: Exception) { } }
    }

    fun flush() {
        if (eventsSinceSave == 0) return
        save()
    }

    private fun afterChange() {
        eventsSinceMaintenance++
        eventsSinceSave++
        if (eventsSinceMaintenance >= MAINTENANCE_EVERY) {
            eventsSinceMaintenance = 0
            decayAndPrune()
        }
        if (eventsSinceSave >= SAVE_EVERY) save()
    }

    /** Fade old counts a little, drop what has faded away, and enforce the caps. */
    private fun decayAndPrune() {
        val uIt = unigrams.entries.iterator()
        while (uIt.hasNext()) {
            val e = uIt.next()
            e.setValue(e.value * DECAY)
            if (e.value < MIN_KEEP) uIt.remove()
        }
        val rowIt = bigrams.entries.iterator()
        while (rowIt.hasNext()) {
            val row = rowIt.next().value
            val cellIt = row.entries.iterator()
            while (cellIt.hasNext()) {
                val e = cellIt.next()
                e.setValue(e.value * DECAY)
                if (e.value < MIN_KEEP) cellIt.remove()
            }
            if (row.isEmpty()) rowIt.remove()
        }
        if (unigrams.size > MAX_UNIGRAMS) {
            val cutoff = unigrams.values.sorted()[unigrams.size - MAX_UNIGRAMS]
            unigrams.entries.removeAll { it.value < cutoff }
        }
        val pairs = bigrams.values.sumOf { row -> row.size }
        if (pairs > MAX_BIGRAMS) {
            val all = ArrayList<Float>(pairs)
            for (row in bigrams.values) all.addAll(row.values)
            all.sort()
            val cutoff = all[pairs - MAX_BIGRAMS]
            for (row in bigrams.values) row.entries.removeAll { it.value < cutoff }
            bigrams.entries.removeAll { it.value.isEmpty() }
        }
        unigramTotal = unigrams.values.sum()
        vocabularyVersion++
    }

    private fun save() {
        val f = file ?: return
        eventsSinceSave = 0
        val sb = StringBuilder()
        for ((w, c) in unigrams) sb.append("U\t").append(w).append('\t').append(c).append('\n')
        for ((p, row) in bigrams) {
            for ((w, c) in row) {
                sb.append("B\t").append(p).append('\t').append(w).append('\t').append(c).append('\n')
            }
        }
        val text = sb.toString()
        writer.execute {
            try {
                val tmp = File(f.parentFile, FILE_NAME + ".tmp")
                tmp.writeText(text)
                if (!tmp.renameTo(f)) {
                    f.writeText(text)
                    tmp.delete()
                }
            } catch (e: Exception) {
                // Losing one save is fine; the next one will catch up.
            }
        }
    }

    companion object {
        private const val FILE_NAME = "learned_words.tsv"
        private const val KNOWN_THRESHOLD = 2f
        private const val MAINTENANCE_EVERY = 400
        private const val SAVE_EVERY = 40
        private const val DECAY = 0.96f
        private const val MIN_KEEP = 0.4f
        private const val MAX_UNIGRAMS = 4000
        private const val MAX_BIGRAMS = 9000
        private val writer = Executors.newSingleThreadExecutor()
    }
}
