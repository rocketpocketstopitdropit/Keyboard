package com.example.customkeyboard

import android.content.Context
import org.json.JSONArray

object ClipboardHistoryStore {
    private const val PREFS_NAME = "clipboard_history"
    private const val KEY = "items"
    private const val MAX_ITEMS = 10

    fun getAll(context: Context): List<String> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(context: Context, text: String) {
        if (text.isBlank()) return
        val current = getAll(context).toMutableList()
        current.remove(text)
        current.add(0, text)
        while (current.size > MAX_ITEMS) current.removeAt(current.size - 1)
        val arr = JSONArray()
        current.forEach { arr.put(it) }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
