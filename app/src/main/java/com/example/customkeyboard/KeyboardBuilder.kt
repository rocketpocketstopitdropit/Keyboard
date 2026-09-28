package com.example.customkeyboard

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

enum class KeyboardPage { LETTERS, SYMBOLS_1, SYMBOLS_2 }

/** Where an editable key lives. row = -1 is the backspace key in the top-right notch. */
data class KeyAddress(val row: Int, val col: Int)

class BuiltKeyboard(
    val root: LinearLayout,
    val keys: List<KeyView>,
    val letterKeys: List<KeyView>
)

/** This key's touch area (widened when it's the predicted key), in `root`'s coordinates. */
fun KeyView.hitRectIn(root: View): Rect {
    val r = localHitRect()
    var p = parent as? View
    while (p != null && p !== root) {
        r.offset(p.left, p.top)
        p = p.parent as? View
    }
    return r
}

/**
 * Builds the whole keyboard: the backspace notch on top, then the panel of key
 * rows. The real keyboard and the Edit Keys preview both use this, so the
 * preview always matches what you actually type on. Each editable key carries
 * its KeyAddress in `tag`.
 */
object KeyboardBuilder {

    fun build(
        context: Context,
        page: KeyboardPage,
        onTap: (KeyConfig) -> Unit,
        onFlick: (KeyConfig, String) -> Unit
    ): BuiltKeyboard {
        val dm = context.resources.displayMetrics
        fun dp(v: Int): Int =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), dm).toInt()

        val matchParent = ViewGroup.LayoutParams.MATCH_PARENT
        val wrapContent = ViewGroup.LayoutParams.WRAP_CONTENT

        val keyHeight = KeyboardPrefs.getKeyHeight(context)
        val keySpacing = KeyboardPrefs.getKeySpacing(context)
        val keyWidthPct = KeyboardPrefs.getKeyWidth(context).coerceIn(30, 100)
        val showHints = KeyboardPrefs.getShowHints(context)
        val showHighlight = KeyboardPrefs.getShowPredictedHighlight(context)
        val showNumberRow = KeyboardPrefs.getShowNumberRow(context)
        val bgColor = context.getColor(R.color.keyboard_background)

        val keys = mutableListOf<KeyView>()
        val letterKeys = mutableListOf<KeyView>()

        fun makeKey(config: KeyConfig, address: KeyAddress?): KeyView {
            val kv = KeyView(
                context = context,
                config = config,
                onTap = onTap,
                onFlick = onFlick,
                showHints = showHints,
                showPredictedHighlight = showHighlight
            )
            kv.tag = address
            keys.add(kv)
            if (config.action == KeyAction.CHAR && config.label.length == 1 &&
                config.label[0].isLetter()
            ) {
                letterKeys.add(kv)
            }
            return kv
        }

        // Transparent at the top-left; only the panel and the tab are painted.
        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        // ----- Notch: the backspace tab above the top-right corner -----
        val notchRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 10f
        }
        notchRow.addView(View(context), LinearLayout.LayoutParams(0, 1, 8.5f))
        val tab = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(6), dp(4), 0)
            val radius = dp(12).toFloat()
            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
        }
        val notchConfig = KeyLayoutStore.effectiveConfig(context, -1, 0, KeyboardLayout.NOTCH_KEY)
        tab.addView(
            makeKey(notchConfig, KeyAddress(-1, 0)),
            LinearLayout.LayoutParams(matchParent, dp(keyHeight))
        )
        notchRow.addView(tab, LinearLayout.LayoutParams(0, wrapContent, 1.5f))
        root.addView(notchRow, LinearLayout.LayoutParams(matchParent, wrapContent))

        // ----- Main panel of key rows -----
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        root.addView(panel, LinearLayout.LayoutParams(matchParent, wrapContent))

        fun addRow(configs: List<KeyConfig>, rowIndex: Int?) {
            // "Key width" shrinks the keys toward the middle using side spacers.
            val side = (100 - keyWidthPct) / 2f
            val wrapper = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 100f
            }
            wrapper.addView(View(context), LinearLayout.LayoutParams(0, 1, side))
            val rowLayout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            wrapper.addView(
                rowLayout,
                LinearLayout.LayoutParams(0, wrapContent, keyWidthPct.toFloat())
            )
            wrapper.addView(View(context), LinearLayout.LayoutParams(0, 1, side))

            configs.forEachIndexed { col, cfg ->
                val address = if (rowIndex != null) KeyAddress(rowIndex, col) else null
                rowLayout.addView(
                    makeKey(cfg, address),
                    LinearLayout.LayoutParams(0, dp(keyHeight), cfg.weight).apply {
                        marginStart = dp(keySpacing)
                        marginEnd = dp(keySpacing)
                    }
                )
            }
            panel.addView(
                wrapper,
                LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                    topMargin = dp(keySpacing)
                    bottomMargin = dp(keySpacing)
                }
            )
        }

        when (page) {
            KeyboardPage.LETTERS -> {
                if (showNumberRow) addRow(KeyboardLayout.NUMBER_ROW, null)
                KeyboardLayout.ROWS.forEachIndexed { rowIndex, row ->
                    addRow(
                        row.mapIndexed { col, def ->
                            KeyLayoutStore.effectiveConfig(context, rowIndex, col, def)
                        },
                        rowIndex
                    )
                }
            }
            KeyboardPage.SYMBOLS_1 -> KeyboardLayout.SYMBOLS_1.forEach { addRow(it, null) }
            KeyboardPage.SYMBOLS_2 -> KeyboardLayout.SYMBOLS_2.forEach { addRow(it, null) }
        }

        return BuiltKeyboard(root, keys, letterKeys)
    }
}
