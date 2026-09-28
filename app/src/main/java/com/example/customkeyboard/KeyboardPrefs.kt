package com.example.customkeyboard

import android.content.Context

object KeyboardPrefs {
    private const val PREFS_NAME = "keyboard_settings"
    private const val KEY_HEIGHT = "key_height_dp"
    private const val KEY_WIDTH = "key_width_pct"
    private const val KEY_SPACING = "key_spacing_dp"
    private const val SHOW_HINTS = "show_corner_hints"
    private const val HAPTIC_INTENSITY = "haptic_intensity"
    private const val SHOW_PREDICTED_HIGHLIGHT = "show_predicted_highlight"
    private const val SHOW_NUMBER_ROW = "show_number_row"

    const val DEFAULT_HEIGHT = 52
    const val DEFAULT_KEY_WIDTH = 100
    const val DEFAULT_SPACING = 2
    const val DEFAULT_SHOW_HINTS = true
    const val DEFAULT_HAPTIC_INTENSITY = 40
    const val DEFAULT_SHOW_PREDICTED_HIGHLIGHT = false
    const val DEFAULT_SHOW_NUMBER_ROW = false

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Up-and-down size of each key, in dp.
    fun getKeyHeight(context: Context): Int = prefs(context).getInt(KEY_HEIGHT, DEFAULT_HEIGHT)
    fun setKeyHeight(context: Context, value: Int) = prefs(context).edit().putInt(KEY_HEIGHT, value).apply()

    // Side-to-side size: how much of the screen width the keys use, in percent.
    fun getKeyWidth(context: Context): Int = prefs(context).getInt(KEY_WIDTH, DEFAULT_KEY_WIDTH)
    fun setKeyWidth(context: Context, value: Int) = prefs(context).edit().putInt(KEY_WIDTH, value).apply()

    fun getKeySpacing(context: Context): Int = prefs(context).getInt(KEY_SPACING, DEFAULT_SPACING)
    fun setKeySpacing(context: Context, value: Int) = prefs(context).edit().putInt(KEY_SPACING, value).apply()

    fun getShowHints(context: Context): Boolean = prefs(context).getBoolean(SHOW_HINTS, DEFAULT_SHOW_HINTS)
    fun setShowHints(context: Context, value: Boolean) = prefs(context).edit().putBoolean(SHOW_HINTS, value).apply()

    fun getHapticIntensity(context: Context): Int =
        prefs(context).getInt(HAPTIC_INTENSITY, DEFAULT_HAPTIC_INTENSITY)
    fun setHapticIntensity(context: Context, value: Int) =
        prefs(context).edit().putInt(HAPTIC_INTENSITY, value).apply()

    fun getShowPredictedHighlight(context: Context): Boolean =
        prefs(context).getBoolean(SHOW_PREDICTED_HIGHLIGHT, DEFAULT_SHOW_PREDICTED_HIGHLIGHT)
    fun setShowPredictedHighlight(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(SHOW_PREDICTED_HIGHLIGHT, value).apply()

    fun getShowNumberRow(context: Context): Boolean =
        prefs(context).getBoolean(SHOW_NUMBER_ROW, DEFAULT_SHOW_NUMBER_ROW)
    fun setShowNumberRow(context: Context, value: Boolean) =
        prefs(context).edit().putBoolean(SHOW_NUMBER_ROW, value).apply()
}
