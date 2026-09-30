package com.example.customkeyboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import kotlin.math.abs

/** One search hit: a small picture to show, and the file that gets sent. */
data class GifResult(val previewUrl: String, val sendUrl: String, val mime: String)

/**
 * GIF search over the Klipy API (Tenor's API was shut down on June 30, 2026).
 * Every function here blocks on the network, so call them through [runAsync].
 *
 * The response parsing is deliberately forgiving: it walks the "files" object
 * of each result and picks the best-looking image URLs, rather than relying on
 * one exact layout.
 */
object GifClient {

    private const val BASE = "https://api.klipy.com/api/v1"
    private const val PER_PAGE = 24

    private val executor = Executors.newFixedThreadPool(4)

    /** Size names, smallest first. Anything not listed counts as medium. */
    private val SIZE_ORDER = listOf(
        "xs", "tiny", "nano", "sm", "small", "md", "medium", "hd", "lg", "large", "original"
    )
    private val MID_RANK = SIZE_ORDER.indexOf("md")

    fun runAsync(block: () -> Unit) {
        executor.execute { block() }
    }

    /** Trending GIFs when [query] is blank, otherwise search results. */
    fun fetchResults(apiKey: String, query: String): List<GifResult> {
        val key = URLEncoder.encode(apiKey.trim(), "UTF-8")
        val q = query.trim()
        val url = if (q.isEmpty()) {
            "$BASE/$key/gifs/trending?per_page=$PER_PAGE"
        } else {
            "$BASE/$key/gifs/search?q=${URLEncoder.encode(q, "UTF-8")}&per_page=$PER_PAGE"
        }
        val body = String(download(url, 2_000_000), Charsets.UTF_8)
        return parse(body)
    }

    private fun parse(body: String): List<GifResult> {
        val root = JSONObject(body)
        if (root.has("result") && !root.optBoolean("result", true)) {
            throw Exception(root.optString("message", "the service rejected the request"))
        }
        val dataNode = root.opt("data")
        val items: JSONArray? = when (dataNode) {
            is JSONArray -> dataNode
            is JSONObject -> dataNode.optJSONArray("data")
            else -> root.optJSONArray("results")
        }
        val out = ArrayList<GifResult>()
        if (items == null) return out
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            // Ads and other non-GIF entries have no "files" object; skip them.
            val files = item.optJSONObject("files") ?: continue
            pick(files)?.let { out.add(it) }
        }
        return out
    }

    private class Candidate(val path: String, val url: String) {
        val mime: String? = mimeFor(path, url)
    }

    private fun mimeFor(path: String, url: String): String? {
        val ext = url.substringBefore('?').lowercase().substringAfterLast('.', "")
        return when (ext) {
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            else -> if (path.lowercase().contains("gif")) "image/gif" else null
        }
    }

    private fun formatPriority(mime: String?): Int = when (mime) {
        "image/gif" -> 0
        "image/webp" -> 1
        "image/png" -> 2
        else -> 3
    }

    private fun sizeRank(path: String): Int {
        for (part in path.lowercase().split('/')) {
            val idx = SIZE_ORDER.indexOf(part)
            if (idx >= 0) return idx
        }
        return MID_RANK
    }

    private fun collect(node: Any?, path: String, out: MutableList<Candidate>) {
        if (node is JSONObject) {
            val url = node.optString("url", "")
            if (url.startsWith("http")) out.add(Candidate(path, url))
            val keys = node.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val child = node.opt(k)
                if (child is JSONObject || child is JSONArray) {
                    collect(child, "$path/$k", out)
                } else if (k != "url" && child is String && child.startsWith("http")) {
                    out.add(Candidate("$path/$k", child))
                }
            }
        } else if (node is JSONArray) {
            for (i in 0 until node.length()) collect(node.opt(i), "$path/$i", out)
        }
    }

    private fun pick(files: JSONObject): GifResult? {
        val all = ArrayList<Candidate>()
        collect(files, "", all)
        val usable = all.filter { it.mime != null }
        if (usable.isEmpty()) return null
        // Show the smallest picture; send a mid-sized GIF (small enough to upload quickly).
        val preview = usable.minByOrNull { sizeRank(it.path) * 10 + formatPriority(it.mime) } ?: return null
        val send = usable.minByOrNull {
            formatPriority(it.mime) * 100 + abs(sizeRank(it.path) - MID_RANK)
        } ?: return null
        return GifResult(preview.url, send.url, send.mime ?: "image/gif")
    }

    // ---------- downloading ----------

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 10000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) CustomKeyboard")
        c.setRequestProperty("Accept", "*/*")
        return c
    }

    fun download(url: String, maxBytes: Int): ByteArray {
        val c = open(url)
        try {
            val code = c.responseCode
            if (code != 200) throw Exception("HTTP $code")
            val out = ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) throw Exception("file too large")
                }
            }
            return out.toByteArray()
        } finally {
            c.disconnect()
        }
    }

    fun downloadTo(url: String, dest: File, maxBytes: Int = 15_000_000) {
        dest.writeBytes(download(url, maxBytes))
    }

    /** First frame of the picture, scaled down to roughly [targetWidth] pixels wide. */
    fun loadBitmap(url: String, targetWidth: Int): Bitmap? {
        return try {
            val bytes = download(url, 4_000_000)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth > 0 && bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: Exception) {
            null
        }
    }
}
