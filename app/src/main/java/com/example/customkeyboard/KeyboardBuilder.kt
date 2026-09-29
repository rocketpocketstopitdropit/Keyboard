package com.example.customkeyboard

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

enum class KeyboardPage { LETTERS, SYMBOLS_1, SYMBOLS_2, EMOJI, CLIPBOARD }
enum class AccessoryAction { SETTINGS, GIF, EMOJI, CLIPBOARD, MIC }

/** Where an editable key lives. row = -1 is the backspace key in the top-right notch. */
data class KeyAddress(val row: Int, val col: Int)

class BuiltKeyboard(
    val root: LinearLayout,
    val keys: List<KeyView>,
    val letterKeys: List<KeyView>,
    val micButton: TextView?
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
 * backspace notch on the right), then the panel of key rows. The real
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
        onAccessory: (AccessoryAction) -> Unit
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

        // ----- Top strip: accessory icons (left) + backspace notch (right) -----
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
        topRow.addView(iconStrip, LinearLayout.LayoutParams(0, wrapContent, 8.5f))

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

        fun addRow(configs: List<KeyConfig>, rowIndex: Int?) {
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
            panel.addView(
                wrapper,
                LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                    topMargin = dp(keySpacing)
                    bottomMargin = dp(keySpacing)
                }
            )
        }

        fun clipboardRows(): List<List<KeyConfig>> {
            if (clipboardHistory.isEmpty()) {
                return listOf(
                    listOf(KeyConfig("Nothing copied yet", weight = 4f, action = KeyAction.SPACER)),
                    listOf(KeyConfig("ABC", weight = 2f, action = KeyAction.LETTERS))
                )
            }
            val rows = clipboardHistory.chunked(2).map { pair ->
                pair.map { full ->
                    val preview = if (full.length > 24) full.take(24) + "…" else full
                    KeyConfig(preview, weight = 2f, commitOverride = full)
                }
            }
            return rows + listOf(listOf(KeyConfig("ABC", weight = 2f, action = KeyAction.LETTERS)))
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
            KeyboardPage.EMOJI -> KeyboardLayout.EMOJI_ROWS.forEach { addRow(it, null) }
            KeyboardPage.CLIPBOARD -> clipboardRows().forEach { addRow(it, null) }
        }

        return BuiltKeyboard(root, keys, letterKeys, micButton)
    }
}
