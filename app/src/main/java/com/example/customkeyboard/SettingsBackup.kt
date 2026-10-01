package com.example.customkeyboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns every keyboard setting (sizes, toggles, and each key's flick
 * characters and colours) into one block of text that can be copied, and
 * restores it from that text. Handy for moving to a new phone or recovering
 * after a reinstall. The GIF API key is left out on purpose.
 */
object SettingsBackup {

    private const val MARKER = "customkeyboard-settings"
    private val STORES = listOf("keyboard_settings", "keyboard_layout_overrides", "user_dictionary")
    private val SKIPPED_KEYS = setOf("klipy_api_key")

    private fun encode(value: Any?): JSONObject? {
        val e = JSONObject()
        when (value) {
            is Boolean -> { e.put("t", "b"); e.put("v", value) }
            is Int -> { e.put("t", "i"); e.put("v", value) }
            is Long -> { e.put("t", "l"); e.put("v", value) }
            is Float -> { e.put("t", "f"); e.put("v", value.toDouble()) }
            is String -> { e.put("t", "s"); e.put("v", value) }
            is Set<*> -> { e.put("t", "ss"); e.put("v", JSONArray(value.map { it.toString() })) }
            else -> return null
        }
        return e
    }

    fun export(context: Context): String {
        val stores = JSONObject()
        for (name in STORES) {
            val entries = JSONObject()
            val all = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
            for ((key, value) in all) {
                if (key in SKIPPED_KEYS) continue
                val encoded = encode(value) ?: continue
                entries.put(key, encoded)
            }
            stores.put(name, entries)
        }
        val root = JSONObject()
        root.put("app", MARKER)
        root.put("version", 1)
        root.put("stores", stores)
        return root.toString()
    }

    /** Restores settings from [text]; returns how many values were restored. Throws if it isn't a backup. */
    fun restore(context: Context, text: String): Int {
        val root = JSONObject(text.trim())
        if (root.optString("app") != MARKER) throw IllegalArgumentException("not a keyboard settings backup")
        val stores = root.getJSONObject("stores")
        var restored = 0
        for (name in STORES) {
            val entries = stores.optJSONObject(name) ?: continue
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            val keys = entries.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key in SKIPPED_KEYS) continue
                val e = entries.getJSONObject(key)
                val known = when (e.getString("t")) {
                    "b" -> { editor.putBoolean(key, e.getBoolean("v")); true }
                    "i" -> { editor.putInt(key, e.getInt("v")); true }
                    "l" -> { editor.putLong(key, e.getLong("v")); true }
                    "f" -> { editor.putFloat(key, e.getDouble("v").toFloat()); true }
                    "s" -> { editor.putString(key, e.getString("v")); true }
                    "ss" -> {
                        val arr = e.getJSONArray("v")
                        val set = HashSet<String>()
                        for (i in 0 until arr.length()) set.add(arr.getString(i))
                        editor.putStringSet(key, set)
                        true
                    }
                    else -> false
                }
                if (known) restored++
            }
            editor.apply()
        }
        return restored
    }
}
