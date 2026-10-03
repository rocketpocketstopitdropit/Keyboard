package com.example.customkeyboard

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs

/** What the word checker found out about one word. */
data class WordInfo(
    val word: String,
    /** Spelled correctly (a real word). */
    val found: Boolean,
    val phonetic: String = "",
    /** Part of speech (may be blank) and definition. */
    val meanings: List<Pair<String, String>> = emptyList(),
    /** Likely intended spellings, when [found] is false. */
    val suggestions: List<String> = emptyList()
)

/**
 * Online spell check and definitions for the word-check panel.
 *
 * Uses two free services that need no key:
 *  - Free Dictionary API (dictionaryapi.dev): definitions; a word it knows is spelled right.
 *  - Datamuse (datamuse.com): words spelled like the one typed, for "did you mean"
 *    and as a second opinion on spelling (it knows more inflected forms).
 *
 * Only words typed or pasted into the word-check panel are ever sent.
 * Every call here blocks on the network, so run it through [runAsync].
 */
object WordLookup {

    private const val MAX_WORDS = 6
    private const val MAX_MEANINGS = 6

    private val executor = Executors.newFixedThreadPool(2)
    private val cache = ConcurrentHashMap<String, WordInfo>()

    fun runAsync(block: () -> Unit) {
        executor.execute { block() }
    }

    /** The separate words in what was typed or pasted (at most [MAX_WORDS], no repeats). */
    fun wordsIn(text: String): List<String> {
        val out = LinkedHashMap<String, String>()
        for (m in Regex("[A-Za-z][A-Za-z'’-]*").findAll(text)) {
            val w = m.value.replace('’', '\'').trimEnd('\'', '-')
            if (w.isEmpty()) continue
            out.putIfAbsent(w.lowercase(), w)
            if (out.size >= MAX_WORDS) break
        }
        return out.values.toList()
    }

    fun cached(word: String): WordInfo? = cache[word.lowercase()]

    /** Looks [word] up online. Throws if neither service can be reached. */
    fun lookup(word: String): WordInfo {
        val key = word.lowercase()
        cache[key]?.let { return it }
        val enc = URLEncoder.encode(key, "UTF-8")

        var found = false
        var phonetic = ""
        val meanings = ArrayList<Pair<String, String>>()
        var dictionaryReached = false
        try {
            val (code, body) = get("https://api.dictionaryapi.dev/api/v2/entries/en/$enc")
            if (code == 200) {
                dictionaryReached = true
                found = true
                parseDictionary(body, meanings)?.let { phonetic = it }
            } else if (code == 404) {
                dictionaryReached = true
            }
        } catch (e: Exception) {
            // Try the second service before giving up.
        }

        val suggestions = ArrayList<String>()
        if (!found || meanings.isEmpty()) {
            try {
                val (code, body) = get("https://api.datamuse.com/words?sp=$enc&md=d&max=8")
                if (code == 200) {
                    val arr = JSONArray(body)
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val w = o.optString("word", "")
                        if (w.isEmpty()) continue
                        if (w.equals(key, ignoreCase = true)) {
                            found = true
                            if (meanings.isEmpty()) parseDatamuseDefs(o.optJSONArray("defs"), meanings)
                        } else if (!w.contains(' ') && suggestions.size < 5) {
                            suggestions.add(w)
                        }
                    }
                } else if (!dictionaryReached) {
                    throw Exception("HTTP $code")
                }
            } catch (e: Exception) {
                if (!dictionaryReached) throw e
            }
        }

        val info = WordInfo(
            word = word,
            found = found,
            phonetic = phonetic,
            meanings = meanings,
            suggestions = if (found) emptyList() else suggestions
        )
        if (cache.size > 300) cache.clear()
        cache[key] = info
        return info
    }

    /** Fills [out] from a Free Dictionary response; returns the pronunciation, if any. */
    private fun parseDictionary(body: String, out: MutableList<Pair<String, String>>): String? {
        val arr = JSONArray(body)
        var phonetic: String? = null
        for (i in 0 until arr.length()) {
            val entry = arr.optJSONObject(i) ?: continue
            if (phonetic.isNullOrBlank()) {
                phonetic = entry.optString("phonetic", "").ifBlank { null }
                val ph = entry.optJSONArray("phonetics")
                if (phonetic == null && ph != null) {
                    for (j in 0 until ph.length()) {
                        val t = ph.optJSONObject(j)?.optString("text", "") ?: ""
                        if (t.isNotBlank()) { phonetic = t; break }
                    }
                }
            }
            val meanings = entry.optJSONArray("meanings") ?: continue
            for (j in 0 until meanings.length()) {
                val m = meanings.optJSONObject(j) ?: continue
                val pos = m.optString("partOfSpeech", "")
                val defs = m.optJSONArray("definitions") ?: continue
                for (k in 0 until minOf(2, defs.length())) {
                    val d = defs.optJSONObject(k)?.optString("definition", "") ?: ""
                    if (d.isNotBlank()) out.add(pos to d)
                    if (out.size >= MAX_MEANINGS) return phonetic
                }
            }
        }
        return phonetic
    }

    /** Datamuse definitions look like "n\tthe act of ...". */
    private fun parseDatamuseDefs(defs: JSONArray?, out: MutableList<Pair<String, String>>) {
        if (defs == null) return
        for (i in 0 until defs.length()) {
            val raw = defs.optString(i, "")
            val tab = raw.indexOf('\t')
            val code = if (tab > 0) raw.substring(0, tab) else ""
            val def = if (tab >= 0) raw.substring(tab + 1) else raw
            val pos = when (code) {
                "n" -> "noun"
                "v" -> "verb"
                "adj" -> "adjective"
                "adv" -> "adverb"
                else -> ""
            }
            if (def.isNotBlank()) out.add(pos to def)
            if (out.size >= MAX_MEANINGS) return
        }
    }

    /** Status code and body (the error body for codes other than 200). */
    private fun get(url: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", "CustomKeyboard (Android)")
        c.setRequestProperty("Accept", "application/json")
        try {
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText().take(400_000) } ?: ""
            return code to body
        } finally {
            c.disconnect()
        }
    }
}

/**
 * A frame that notices a sideways swipe anywhere inside it, even over
 * buttons or a scrolling list, and reports its direction: -1 for right to
 * left, +1 for left to right. Vertical scrolling inside still works.
 */
class SwipeFrame(context: Context, private val onSwipe: (Int) -> Unit) : FrameLayout(context) {

    private val threshold = 48f * resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var fired = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                fired = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (fired) return true
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dx) >= threshold && abs(dx) > 1.5f * abs(dy) && abs(dx) > slop) {
                    fired = true
                    // Whatever was being pressed underneath shouldn't also click.
                    val cancel = MotionEvent.obtain(ev)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                    onSwipe(if (dx < 0) -1 else 1)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (fired) {
                    fired = false
                    return true
                }
            }
        }
        // Keep receiving the gesture even when nothing underneath wants it.
        super.dispatchTouchEvent(ev)
        return true
    }
}
