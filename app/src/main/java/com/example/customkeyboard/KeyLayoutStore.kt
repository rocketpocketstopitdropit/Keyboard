package com.example.customkeyboard

import android.content.Context
import org.json.JSONObject

data class KeyOverride(
    val label: String,
    val topLeft: String?,
    val topRight: String?,
    val bottomLeft: String?,
    val bottomRight: String?,
    val colorHex: String?,
    val heightDp: Int?
)

object KeyLayoutStore {
    private const val PREFS_NAME = "keyboard_layout_overrides"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun keyFor(row: Int, col: Int) = "r${row}_c${col}"

    private fun optStringOrNull(obj: JSONObject, key: String): String? =
        if (obj.has(key) && !obj.isNull(key)) obj.getString(key) else null

    private fun optIntOrNull(obj: JSONObject, key: String): Int? =
        if (obj.has(key) && !obj.isNull(key)) obj.getInt(key) else null

    fun getOverride(context: Context, row: Int, col: Int): KeyOverride? {
        val json = prefs(context).getString(keyFor(row, col), null) ?: return null
        return try {
            val obj = JSONObject(json)
            KeyOverride(
                label = obj.getString("label"),
                topLeft = optStringOrNull(obj, "topLeft"),
                topRight = optStringOrNull(obj, "topRight"),
                bottomLeft = optStringOrNull(obj, "bottomLeft"),
                bottomRight = optStringOrNull(obj, "bottomRight"),
                colorHex = optStringOrNull(obj, "colorHex"),
                heightDp = optIntOrNull(obj, "heightDp")
            )
        } catch (e: Exception) {
            null
        }
    }

    fun setOverride(context: Context, row: Int, col: Int, override: KeyOverride) {
        val obj = JSONObject()
        obj.put("label", override.label)
        obj.put("topLeft", override.topLeft)
        obj.put("topRight", override.topRight)
        obj.put("bottomLeft", override.bottomLeft)
        obj.put("bottomRight", override.bottomRight)
        obj.put("colorHex", override.colorHex)
        if (override.heightDp != null) obj.put("heightDp", override.heightDp)
        prefs(context).edit().putString(keyFor(row, col), obj.toString()).apply()
    }

    fun clearOverride(context: Context, row: Int, col: Int) {
        prefs(context).edit().remove(keyFor(row, col)).apply()
    }

    fun effectiveConfig(context: Context, row: Int, col: Int, default: KeyConfig): KeyConfig {
        val o = getOverride(context, row, col) ?: return default
        return default.copy(
            label = o.label,
            topLeft = o.topLeft,
            topRight = o.topRight,
            bottomLeft = o.bottomLeft,
            bottomRight = o.bottomRight,
            colorHex = o.colorHex,
            heightDp = o.heightDp
        )
    }
}
