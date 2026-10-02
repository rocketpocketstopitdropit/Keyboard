package com.example.customkeyboard

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.text.TextUtils
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

enum class KeyboardPage { LETTERS, SYMBOLS_1, SYMBOLS_2, EMOJI, CLIPBOARD }
enum class AccessoryAction { SETTINGS, GIF, EMOJI, CLIPBOARD, MIC }

/** Where an editable key lives. row = -1 is the enter key in the top-right notch. */
data class KeyAddress(val row: Int, val col: Int)

class BuiltKeyboard(
    val root: LinearLayout,
    val keys: List<KeyView>,
    val letterKeys: List<KeyView>,
    val micButton: TextView?,
    /** Holds the hotkeys and the suggestion strip, one on top of the other. */
    val stripHost: FrameLayout,
    /** The row of hotkeys (settings, GIF, emoji, clipboard, mic). */
    val iconStrip: View,
    /** The three word suggestions; hidden until the service shows it. */
    val suggestionStrip: View,
    /** Left, centre (best guess), right. */
    val suggestionViews: List<TextView>
)

private fun offsetToRoot(rect: Rect, view: View, root: View): Rect {
    val r = Rect(rect)
    var p = view.parent as? View
    while (p != null && p !== root) {
        r.offset(p.left, p.top)
        p = p.parent as? View
    }
    return r
}

/** This key's touch area (widened when it's the predicted key), in `root`'s coordinates. */
fun KeyView.hitRectIn(root: View): Rect = offsetToRoot(localHitRect(), this, root)

/** This key's TRUE touch area, never widened, in `root`'s coordinates. */
fun KeyView.rawHitRectIn(root: View): Rect = offsetToRoot(rawLocalRect(), this, root)

/**
 * Builds the whole keyboard: a top strip (accessory icons on the left, the
 * enter-key notch on the right), then the panel of key rows. The real
 * keyboard and the Edit Keys preview both use this, so the preview always
 * matches what you actually type on. Each editable key carries its
 * KeyAddress in `tag`.
 */
object KeyboardBuilder {

    fun build(
        context: Context,
        page: KeyboardPage,
        clipboardHistory: List<String> = emptyList(),
        onTap: (KeyConfig) -> Unit,
        onFlick: (KeyConfig, String) -> Unit,
        onAccessory: (AccessoryAction) -> Unit,
        onSuggestion: (String) -> Unit = {}
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

        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        // ----- Top strip: accessory icons (left) + enter-key notch (right) -----
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 10f
        }

        fun accessoryIcon(text: String, weight: Float, action: AccessoryAction): TextView =
            TextView(context).apply {
                this.text = text
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, dp(36), weight)
                setOnClickListener { onAccessory(action) }
            }

        val iconStrip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(2), 0)
        }
        iconStrip.addView(accessoryIcon("⚙", 1f, AccessoryAction.SETTINGS))
        iconStrip.addView(accessoryIcon("GIF", 1f, AccessoryAction.GIF))
        iconStrip.addView(accessoryIcon("🙂", 1f, AccessoryAction.EMOJI))
        iconStrip.addView(accessoryIcon("📋", 1f, AccessoryAction.CLIPBOARD))
        val micButton = accessoryIcon("🎤", 1f, AccessoryAction.MIC)
        iconStrip.addView(micButton)

        // The suggestion strip sits in the same spot as the hotkeys; the
        // service swaps between them (hotkeys when idle, suggestions while
        // a word is being typed).
        val suggestionViews = mutableListOf<TextView>()
        val suggestionStrip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(2), 0)
            visibility = View.GONE
        }
        for (slot in 0 until 3) {
            if (slot > 0) {
                suggestionStrip.addView(
                    View(context).apply { setBackgroundColor(Color.argb(70, 255, 255, 255)) },
                    LinearLayout.LayoutParams(dp(1), dp(20))
                )
            }
            val tv = TextView(context).apply {
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(4), 0, dp(4), 0)
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f)
                setOnClickListener {
                    val word = text.toString()
                    if (word.isNotEmpty()) onSuggestion(word)
                }
            }
            suggestionViews.add(tv)
            suggestionStrip.addView(tv)
        }

        val stripHost = FrameLayout(context)
        stripHost.addView(iconStrip, FrameLayout.LayoutParams(matchParent, wrapContent))
        stripHost.addView(suggestionStrip, FrameLayout.LayoutParams(matchParent, wrapContent))
        topRow.addView(stripHost, LinearLayout.LayoutParams(0, wrapContent, 8.5f))

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
            LinearLayout.LayoutParams(matchParent, dp(notchConfig.heightDp ?: keyHeight))
        )
        topRow.addView(tab, LinearLayout.LayoutParams(0, wrapContent, 1.5f))
        root.addView(topRow, LinearLayout.LayoutParams(matchParent, wrapContent))

        // ----- Main panel of key rows -----
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        root.addView(panel, LinearLayout.LayoutParams(matchParent, wrapContent))

        /** Centres [content] using the "Key width" setting, with empty space either side. */
        fun widthWrapped(content: View): LinearLayout {
            val side = (100 - keyWidthPct) / 2f
            val wrapper = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 100f
            }
            wrapper.addView(View(context), LinearLayout.LayoutParams(0, 1, side))
            wrapper.addView(content, LinearLayout.LayoutParams(0, wrapContent, keyWidthPct.toFloat()))
            wrapper.addView(View(context), LinearLayout.LayoutParams(0, 1, side))
            return wrapper
        }

        fun rowParams() = LinearLayout.LayoutParams(matchParent, wrapContent).apply {
            topMargin = dp(keySpacing)
            bottomMargin = dp(keySpacing)
        }

        fun fillRow(rowLayout: LinearLayout, configs: List<KeyConfig>, rowIndex: Int?) {
            configs.forEachIndexed { col, cfg ->
                val rowHeight = dp(cfg.heightDp ?: keyHeight)
                if (cfg.action == KeyAction.SPACER) {
                    rowLayout.addView(
                        View(context),
                        LinearLayout.LayoutParams(0, rowHeight, cfg.weight).apply {
                            marginStart = dp(keySpacing)
                            marginEnd = dp(keySpacing)
                        }
                    )
                } else {
                    val address = if (rowIndex != null) KeyAddress(rowIndex, col) else null
                    rowLayout.addView(
                        makeKey(cfg, address),
                        LinearLayout.LayoutParams(0, rowHeight, cfg.weight).apply {
                            marginStart = dp(keySpacing)
                            marginEnd = dp(keySpacing)
                        }
                    )
                }
            }
        }

        fun addRow(configs: List<KeyConfig>, rowIndex: Int?) {
            val rowLayout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            fillRow(rowLayout, configs, rowIndex)
            panel.addView(widthWrapped(rowLayout), rowParams())
        }

        /**
         * Two rows side by side with one tall key: the upper row's last key
         * runs down beside the lower row. The upper row keeps its exact
         * spacing; the lower row stretches to fill the width left of the tall key.
         */
        fun addRowPair(upper: List<KeyConfig>, upperIndex: Int?, lower: List<KeyConfig>, lowerIndex: Int?) {
            val tall = upper.last()
            val upperRest = upper.dropLast(1)

            val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            fillRow(top, upperRest, upperIndex)
            val bottom = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            fillRow(bottom, lower, lowerIndex)
            val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            // Same gap between the two rows as between any other pair of rows.
            left.addView(top, LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                bottomMargin = dp(keySpacing)
            })
            left.addView(bottom, LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                topMargin = dp(keySpacing)
            })

            val block = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val restWeight = upperRest.sumOf { it.weight.toDouble() }.toFloat()
            block.addView(left, LinearLayout.LayoutParams(0, wrapContent, restWeight))
            val address = if (upperIndex != null) KeyAddress(upperIndex, upper.lastIndex) else null
            block.addView(
                makeKey(tall, address),
                // Fills the full height of both rows.
                LinearLayout.LayoutParams(0, matchParent, tall.weight).apply {
                    marginStart = dp(keySpacing)
                    marginEnd = dp(keySpacing)
                }
            )
            panel.addView(widthWrapped(block), rowParams())
        }

        /** Adds rows in order, pairing a row that ends in a tall key with the row below it. */
        fun addRows(rows: List<List<KeyConfig>>, indexed: Boolean) {
            var i = 0
            while (i < rows.size) {
                val row = rows[i]
                val index = if (indexed) i else null
                if (row.isNotEmpty() && row.last().spansTwoRows && i + 1 < rows.size) {
                    addRowPair(row, index, rows[i + 1], if (indexed) i + 1 else null)
                    i += 2
                } else {
                    addRow(row, index)
                    i++
                }
            }
        }

        fun clipboardRows(): List<List<KeyConfig>> {
            if (clipboardHistory.isEmpty()) {
                return listOf(
                    listOf(KeyConfig("Nothing copied yet", weight = 4f, action = KeyAction.SPACER)),
                    KeyboardLayout.PICKER_BOTTOM_ROW
                )
            }
            val rows = clipboardHistory.chunked(2).map { pair ->
                pair.map { full ->
                    val preview = if (full.length > 24) full.take(24) + "…" else full
                    KeyConfig(preview, weight = 2f, commitOverride = full)
                }
            }
            return rows + listOf(KeyboardLayout.PICKER_BOTTOM_ROW)
        }

        when (page) {
            KeyboardPage.LETTERS -> {
                if (showNumberRow) addRow(KeyboardLayout.NUMBER_ROW, null)
                val rows = KeyboardLayout.ROWS.mapIndexed { rowIndex, row ->
                    row.mapIndexed { col, def -> KeyLayoutStore.effectiveConfig(context, rowIndex, col, def) }
                }
                addRows(rows, indexed = true)
            }
            KeyboardPage.SYMBOLS_1 -> addRows(KeyboardLayout.SYMBOLS_1, indexed = false)
            KeyboardPage.SYMBOLS_2 -> addRows(KeyboardLayout.SYMBOLS_2, indexed = false)
            KeyboardPage.EMOJI -> addRows(KeyboardLayout.EMOJI_ROWS, indexed = false)
            KeyboardPage.CLIPBOARD -> addRows(clipboardRows(), indexed = false)
        }

        return BuiltKeyboard(root, keys, letterKeys, micButton, stripHost, iconStrip, suggestionStrip, suggestionViews)
    }
}
