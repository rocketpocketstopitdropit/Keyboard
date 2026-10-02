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

    private const val LAYOUT_VERSION_KEY = "layout_version"
    private const val LAYOUT_VERSION = 2

    /**
     * Layout 2 swapped enter and backspace: enter moved up into the notch and
     * backspace down beside the bottom rows. Carry any customisations along
     * with the key they were made to (old notch -> new backspace spot, old
     * enter spot -> notch). Runs again after restoring an older backup.
     */
    private fun migrate(context: Context) {
        val p = prefs(context)
        if (p.getInt(LAYOUT_VERSION_KEY, 1) >= LAYOUT_VERSION) return
        run {
            val oldBackspace = p.getString(keyFor(-1, 0), null)
            val oldEnter = p.getString(keyFor(3, 4), null)
            val e = p.edit()
            e.remove(keyFor(-1, 0))
            e.remove(keyFor(3, 4))
            if (oldBackspace != null) e.putString(keyFor(2, 8), oldBackspace)
            if (oldEnter != null) e.putString(keyFor(-1, 0), oldEnter)
            e.putInt(LAYOUT_VERSION_KEY, LAYOUT_VERSION)
            e.apply()
        }
    }

    fun getOverride(context: Context, row: Int, col: Int): KeyOverride? {
        migrate(context)
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
