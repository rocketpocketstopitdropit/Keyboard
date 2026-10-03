package com.example.customkeyboard

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.text.TextUtils
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sin

enum class KeyboardPage { LETTERS, SYMBOLS_1, SYMBOLS_2, EMOJI, CLIPBOARD }
enum class AccessoryAction { SETTINGS, GIF, EMOJI, CLIPBOARD, MIC }

/** Where an editable key lives. row = -1 is the enter key in the top-right notch. */
data class KeyAddress(val row: Int, val col: Int)

/** Colours for a layout style. */
data class KeyPalette(
    val board: Int,
    val key: Int,
    val special: Int,
    val light: Int,
    val dark: Int,
    val accent: Int,
    val onAccent: Int,
    val text: Int,
    val dimText: Int
)

object Palettes {
    /** Split Thumb: graphite keys with a teal accent. */
    val SPLIT = KeyPalette(
        board = Color.parseColor("#121417"),
        key = Color.parseColor("#25282E"),
        special = Color.parseColor("#1A1D21"),
        light = Color.parseColor("#363A42"),
        dark = Color.parseColor("#0F1114"),
        accent = Color.parseColor("#5CCFB9"),
        onAccent = Color.parseColor("#0F1A18"),
        text = Color.parseColor("#E9EBEE"),
        dimText = Color.parseColor("#A9B0BA")
    )
}

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
    val suggestionViews: List<TextView>,
    /**
     * Split Thumb: the suggestions have a place of their own and stay put, so
     * the hotkeys never have to make way for them.
     */
    val separateSuggestions: Boolean = false,
    /** Room the GIF search box should leave on its right (the enter key sits there). */
    val stripEndInset: Int = 0
)

/**
 * A view's on-screen box in [root]'s coordinates, following any rotation on
 * the way up (Split Thumb's tilted halves). A tilted key gets the upright box
 * around it, which is what touch hit-testing needs.
 */
private fun boxInRoot(view: View, root: View): Rect {
    val r = RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())
    var v: View? = view
    while (v != null && v !== root) {
        val m = v.matrix
        if (!m.isIdentity) m.mapRect(r)
        r.offset((v.left - v.scrollX).toFloat(), (v.top - v.scrollY).toFloat())
        v = v.parent as? View
    }
    return Rect(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt())
}

/** This key's touch area (widened when it's the predicted key), in `root`'s coordinates. */
fun KeyView.hitRectIn(root: View): Rect {
    val r = boxInRoot(this, root)
    if (isPredicted) r.inset(-predictedExpandPx, -predictedExpandPx)
    return r
}

/** This key's TRUE touch area, never widened, in `root`'s coordinates. */
fun KeyView.rawHitRectIn(root: View): Rect = boxInRoot(this, root)

/**
 * Lays out Split Thumb's letter keys on a wide screen: the top three rows
 * split into two halves tilted toward the thumbs, the bottom row straight
 * across, and backspace standing upright beside the bottom two rows. Sizes
 * come from a "key unit" (a tenth of the width, less the middle gap), so the
 * layout fits any wide screen.
 */
private class SplitKeysLayout(
    context: Context,
    private val gapUnits: Float,
    private val tiltDeg: Float,
    private val rowHeight: Int,
    private val rowGap: Int,
    private val keyMargin: Int
) : ViewGroup(context) {

    lateinit var leftHalf: View
    lateinit var rightHalf: View
    lateinit var tallKey: View
    lateinit var bottomRow: View

    private var unit = 0f
    private var lift = 0

    private fun rows3() = 3 * rowHeight + 2 * rowGap

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val inner = (w - paddingLeft - paddingRight).coerceAtLeast(0)
        unit = inner / (10f + gapUnits)
        // The outer corners of the tilted halves rise a little; leave room for that.
        lift = ceil(5.5f * unit * sin(Math.toRadians(tiltDeg.toDouble()))).toInt()
        fun exact(v: View, width: Int, height: Int) = v.measure(
            MeasureSpec.makeMeasureSpec(width.coerceAtLeast(0), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height.coerceAtLeast(0), MeasureSpec.EXACTLY)
        )
        exact(leftHalf, (5.5f * unit).roundToInt(), rows3())
        exact(rightHalf, (5f * unit).roundToInt(), rows3())
        exact(tallKey, (1.5f * unit).roundToInt() - 2 * keyMargin, 2 * rowHeight + rowGap)
        exact(bottomRow, ((8.5f + gapUnits) * unit).roundToInt(), rowHeight)
        val h = paddingTop + lift + rows3() + rowGap + rowHeight + paddingBottom
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val x0 = paddingLeft
        val y0 = paddingTop + lift
        val gap = gapUnits * unit
        leftHalf.layout(x0, y0, x0 + leftHalf.measuredWidth, y0 + leftHalf.measuredHeight)
        val rx = x0 + (5f * unit + gap).roundToInt()
        rightHalf.layout(rx, y0, rx + rightHalf.measuredWidth, y0 + rightHalf.measuredHeight)
        val tx = x0 + ((8.5f * unit) + gap).roundToInt() + keyMargin
        val ty = y0 + 2 * (rowHeight + rowGap)
        tallKey.layout(tx, ty, tx + tallKey.measuredWidth, ty + tallKey.measuredHeight)
        val by = y0 + rows3() + rowGap
        bottomRow.layout(x0, by, x0 + bottomRow.measuredWidth, by + bottomRow.measuredHeight)

        // Each half turns about its inner bottom corner, so the inner edges
        // (by the space bar) stay put and the outer edges lean toward the thumbs.
        leftHalf.pivotX = leftHalf.measuredWidth.toFloat()
        leftHalf.pivotY = leftHalf.measuredHeight.toFloat()
        leftHalf.rotation = tiltDeg
        rightHalf.pivotX = 0f
        rightHalf.pivotY = rightHalf.measuredHeight.toFloat()
        rightHalf.rotation = -tiltDeg
    }
}

/**
 * Builds the whole keyboard: a top strip (hotkeys, suggestions and the enter
 * key), then the panel of key rows. The real keyboard and the Edit Keys
 * preview both use this, so the preview always matches what you actually type
 * on. Each editable key carries its KeyAddress in `tag`.
 *
 * Two styles share the same keys in the same order, so customised keys apply
 * to both: the original layout, and Split Thumb (split halves on a wide
 * screen such as an unfolded Fold, a compact version on a narrow one).
 */
object KeyboardBuilder {

    /** Screens at least this wide (in dp) get Split Thumb's split halves. */
    private const val WIDE_SCREEN_DP = 600

    /** Split Thumb's middle gap, in key widths. */
    private const val SPLIT_GAP_UNITS = 1.2f

    /** How far each half leans toward its thumb, in degrees. */
    private const val SPLIT_TILT_DEG = 2.5f

    fun build(
        context: Context,
        page: KeyboardPage,
        clipboardHistory: List<String> = emptyList(),
        onTap: (KeyConfig) -> Unit,
        onFlick: (KeyConfig, String) -> Unit,
        onAccessory: (AccessoryAction) -> Unit,
        onSuggestion: (String) -> Unit = {},
        /** A flick up on the suggestion area (opens the word checker). */
        onStripFlickUp: () -> Unit = {},
        splitStyle: Boolean = KeyboardPrefs.getSplitLayout(context)
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
        val wide = context.resources.configuration.screenWidthDp >= WIDE_SCREEN_DP
        val palette = if (splitStyle) Palettes.SPLIT else null
        val bgColor = palette?.board ?: context.getColor(R.color.keyboard_background)
        val iconColor = palette?.dimText ?: Color.WHITE

        val keys = mutableListOf<KeyView>()
        val letterKeys = mutableListOf<KeyView>()

        // Split Thumb marks the home keys (F and J) so they can be found without looking.
        val homeKeys = setOf(KeyAddress(1, 3), KeyAddress(1, 6))

        fun makeKey(config: KeyConfig, address: KeyAddress?): KeyView {
            val accentEnter = palette != null && config.action == KeyAction.ENTER && config.colorHex == null
            val kv = KeyView(
                context = context,
                config = config,
                onTap = onTap,
                onFlick = onFlick,
                showHints = showHints,
                showPredictedHighlight = showHighlight,
                palette = palette,
                fillColor = if (accentEnter) palette!!.accent else null,
                labelColor = when {
                    accentEnter -> palette!!.onAccent
                    palette != null && config.action != KeyAction.CHAR -> palette.dimText
                    palette != null -> palette.text
                    else -> null
                },
                bumpColor = if (palette != null && address != null && address in homeKeys) palette.accent else null
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

        /**
         * A flick up on [v] calls [onStripFlickUp] instead of tapping it. Checked
         * when the finger lifts, so the keyboard isn't rebuilt mid-gesture.
         */
        val flickUpPx = dp(22).toFloat()
        fun flickUpAware(v: View) {
            var downX = 0f
            var downY = 0f
            v.setOnTouchListener { view, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX
                        downY = e.rawY
                        // Plain (non-button) parts must claim the touch to see the rest of it.
                        !view.isClickable
                    }
                    MotionEvent.ACTION_UP -> {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (dy <= -flickUpPx && abs(dy) > abs(dx)) {
                            view.isPressed = false
                            view.cancelLongPress()
                            view.post { onStripFlickUp() }
                            true
                        } else {
                            !view.isClickable
                        }
                    }
                    else -> !view.isClickable
                }
            }
        }

        // ----- Hotkeys and suggestions (shared by every style; placed below) -----

        fun accessoryIcon(text: String, action: AccessoryAction): TextView =
            TextView(context).apply {
                this.text = text
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(iconColor)
                setOnClickListener { onAccessory(action) }
            }

        val iconStrip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icons = listOf(
            accessoryIcon("⚙", AccessoryAction.SETTINGS),
            accessoryIcon("GIF", AccessoryAction.GIF),
            accessoryIcon("🙂", AccessoryAction.EMOJI),
            accessoryIcon("📋", AccessoryAction.CLIPBOARD),
            accessoryIcon("🎤", AccessoryAction.MIC)
        )
        val micButton = icons.last()
        for (icon in icons) {
            // On a wide Split Thumb keyboard the hotkeys keep to the left; otherwise they share the row evenly.
            val lp = if (splitStyle && wide) LinearLayout.LayoutParams(dp(48), dp(40))
            else LinearLayout.LayoutParams(0, dp(36), 1f)
            iconStrip.addView(icon, lp)
        }

        val suggestionViews = mutableListOf<TextView>()
        val suggestionStrip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (palette != null) {
                setPadding(dp(4), 0, dp(4), 0)
                background = GradientDrawable().apply {
                    setColor(palette.special)
                    cornerRadius = dp(20).toFloat()
                }
            } else {
                visibility = View.GONE
            }
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
                setTextColor(palette?.text ?: Color.WHITE)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(4), 0, dp(4), 0)
                isClickable = true
                setOnClickListener {
                    val word = text.toString()
                    if (word.isNotEmpty()) onSuggestion(word)
                }
            }
            val lp = if (splitStyle && wide) LinearLayout.LayoutParams(dp(132), dp(40))
            else LinearLayout.LayoutParams(0, dp(36), 1f)
            suggestionViews.add(tv)
            suggestionStrip.addView(tv, lp)
            flickUpAware(tv)
        }
        flickUpAware(suggestionStrip)
        if (!splitStyle) {
            // On the original layout the hotkeys sit where the suggestions go when not typing.
            flickUpAware(iconStrip)
            for (icon in icons) flickUpAware(icon)
        }

        val stripHost = FrameLayout(context)
        val enterConfig = KeyLayoutStore.effectiveConfig(context, -1, 0, KeyboardLayout.NOTCH_KEY)
        var stripEndInset = 0

        if (!splitStyle) {
            // ----- Original: hotkeys (left, swapping with suggestions) + enter-key notch (right) -----
            iconStrip.setPadding(dp(4), dp(4), dp(2), 0)
            suggestionStrip.setPadding(dp(4), dp(4), dp(2), 0)
            val topRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                weightSum = 10f
            }
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
            tab.addView(
                makeKey(enterConfig, KeyAddress(-1, 0)),
                LinearLayout.LayoutParams(matchParent, dp(enterConfig.heightDp ?: keyHeight))
            )
            topRow.addView(tab, LinearLayout.LayoutParams(0, wrapContent, 1.5f))
            root.addView(topRow, LinearLayout.LayoutParams(matchParent, wrapContent))
        } else if (wide) {
            // ----- Split Thumb, wide: hotkeys left, suggestions centred, enter top right -----
            val stripHeight = dp(44)
            val top = FrameLayout(context).apply {
                setBackgroundColor(bgColor)
                setPadding(dp(8), dp(6), dp(8), 0)
            }
            val line = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            line.addView(iconStrip, LinearLayout.LayoutParams(0, stripHeight, 1f))
            line.addView(suggestionStrip, LinearLayout.LayoutParams(wrapContent, dp(40)))
            line.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            stripHost.addView(line, FrameLayout.LayoutParams(matchParent, stripHeight))
            top.addView(stripHost, FrameLayout.LayoutParams(matchParent, stripHeight))
            val enterWidth = dp(100)
            top.addView(
                makeKey(enterConfig, KeyAddress(-1, 0)),
                FrameLayout.LayoutParams(enterWidth, stripHeight, Gravity.END or Gravity.CENTER_VERTICAL)
            )
            stripEndInset = enterWidth + dp(8)
            root.addView(top, LinearLayout.LayoutParams(matchParent, wrapContent))
        } else {
            // ----- Split Thumb, compact: hotkeys on top, then suggestions with enter beside them -----
            val top = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(bgColor)
                setPadding(dp(6), dp(4), dp(6), 0)
            }
            val inner = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            inner.addView(iconStrip, LinearLayout.LayoutParams(matchParent, dp(36)))
            val second = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
            second.addView(suggestionStrip, LinearLayout.LayoutParams(0, dp(40), 1f))
            second.addView(
                makeKey(enterConfig, KeyAddress(-1, 0)),
                LinearLayout.LayoutParams(dp(60), dp(40)).apply { marginStart = dp(6) }
            )
            inner.addView(second, LinearLayout.LayoutParams(matchParent, wrapContent).apply { topMargin = dp(4) })
            // A search box laid over the strip leaves the enter key showing.
            stripEndInset = dp(66)
            stripHost.addView(inner, FrameLayout.LayoutParams(matchParent, wrapContent))
            top.addView(stripHost, LinearLayout.LayoutParams(matchParent, wrapContent))
            root.addView(top, LinearLayout.LayoutParams(matchParent, wrapContent))
        }

        // ----- Main panel of key rows -----
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            setPadding(dp(4), dp(if (splitStyle) 4 else 6), dp(4), dp(6))
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

        fun spacer(weight: Float): Pair<KeyConfig, KeyAddress?> =
            KeyConfig("", weight = weight, action = KeyAction.SPACER) to null

        /** The keys of a row, each with its address (or none, for keys that can't be edited). */
        fun withAddresses(configs: List<KeyConfig>, rowIndex: Int?): List<Pair<KeyConfig, KeyAddress?>> =
            configs.mapIndexed { col, cfg -> cfg to rowIndex?.let { KeyAddress(it, col) } }

        fun fillRow(rowLayout: LinearLayout, items: List<Pair<KeyConfig, KeyAddress?>>, fixedHeight: Int? = null) {
            for ((cfg, address) in items) {
                val rowHeight = fixedHeight ?: dp(cfg.heightDp ?: keyHeight)
                val lp = LinearLayout.LayoutParams(0, rowHeight, cfg.weight).apply {
                    marginStart = dp(keySpacing)
                    marginEnd = dp(keySpacing)
                }
                if (cfg.action == KeyAction.SPACER) {
                    rowLayout.addView(View(context), lp)
                } else {
                    rowLayout.addView(makeKey(cfg, address), lp)
                }
            }
        }

        fun addRow(items: List<Pair<KeyConfig, KeyAddress?>>) {
            val rowLayout = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            fillRow(rowLayout, items)
            panel.addView(widthWrapped(rowLayout), rowParams())
        }

        /**
         * Two rows side by side with one tall key: the upper row's last key
         * runs down beside the lower row. The upper row keeps its exact
         * spacing; the lower row stretches to fill the width left of the tall key.
         */
        fun addRowPair(upper: List<Pair<KeyConfig, KeyAddress?>>, lower: List<Pair<KeyConfig, KeyAddress?>>) {
            val (tall, tallAddress) = upper.last()
            val upperRest = upper.dropLast(1)

            val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            fillRow(top, upperRest)
            val bottom = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            fillRow(bottom, lower)
            val left = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            // Same gap between the two rows as between any other pair of rows.
            left.addView(top, LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                bottomMargin = dp(keySpacing)
            })
            left.addView(bottom, LinearLayout.LayoutParams(matchParent, wrapContent).apply {
                topMargin = dp(keySpacing)
            })

            val block = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val restWeight = upperRest.sumOf { it.first.weight.toDouble() }.toFloat()
            block.addView(left, LinearLayout.LayoutParams(0, wrapContent, restWeight))
            block.addView(
                makeKey(tall, tallAddress),
                // Fills the full height of both rows.
                LinearLayout.LayoutParams(0, matchParent, tall.weight).apply {
                    marginStart = dp(keySpacing)
                    marginEnd = dp(keySpacing)
                }
            )
            panel.addView(widthWrapped(block), rowParams())
        }

        /** Adds rows in order, pairing a row that ends in a tall key with the row below it. */
        fun addRows(rows: List<List<Pair<KeyConfig, KeyAddress?>>>) {
            var i = 0
            while (i < rows.size) {
                val row = rows[i]
                if (row.isNotEmpty() && row.last().first.spansTwoRows && i + 1 < rows.size) {
                    addRowPair(row, rows[i + 1])
                    i += 2
                } else {
                    addRow(row)
                    i++
                }
            }
        }

        fun plainRows(rows: List<List<KeyConfig>>) = addRows(rows.map { withAddresses(it, null) })

        /** The letter rows with any customisations applied, each key with its address. */
        fun letterRows(): List<List<Pair<KeyConfig, KeyAddress?>>> =
            KeyboardLayout.ROWS.mapIndexed { rowIndex, row ->
                withAddresses(
                    row.mapIndexed { col, def -> KeyLayoutStore.effectiveConfig(context, rowIndex, col, def) },
                    rowIndex
                )
            }

        /** The bottom row with the cursor key added after the space bar, which gives up one key's width for it. */
        fun withCursorKey(row: List<Pair<KeyConfig, KeyAddress?>>, spaceWeight: Float? = null): List<Pair<KeyConfig, KeyAddress?>> {
            val out = ArrayList<Pair<KeyConfig, KeyAddress?>>()
            for ((cfg, address) in row) {
                if (cfg.action == KeyAction.SPACE) {
                    out.add(cfg.copy(weight = spaceWeight ?: (cfg.weight - 1f).coerceAtLeast(1f)) to address)
                    out.add(KeyboardLayout.CURSOR_KEY to null)
                } else {
                    out.add(cfg to address)
                }
            }
            return out
        }

        /** Split Thumb on a wide screen: tilted halves, a straight bottom row, upright backspace. */
        fun addSplitLetters(rows: List<List<Pair<KeyConfig, KeyAddress?>>>) {
            val rowHeight = dp(keyHeight)
            val rowGap = 2 * dp(keySpacing)
            val margin = dp(keySpacing)
            val r0 = rows[0]
            val r1 = rows[1]
            val r2 = rows[2]

            fun unitKeys(items: List<Pair<KeyConfig, KeyAddress?>>) =
                items.map { (cfg, address) -> cfg.copy(weight = 1f) to address }

            fun half(halfRows: List<List<Pair<KeyConfig, KeyAddress?>>>): LinearLayout {
                val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                halfRows.forEachIndexed { i, items ->
                    val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    fillRow(row, items, rowHeight)
                    col.addView(row, LinearLayout.LayoutParams(matchParent, rowHeight).apply {
                        if (i > 0) topMargin = rowGap
                    })
                }
                return col
            }

            // The rows split where they do on the original: T|Y, G|H, V|B.
            val left = half(listOf(
                unitKeys(r0.subList(0, 5)) + spacer(0.5f),
                listOf(spacer(0.5f)) + unitKeys(r1.subList(0, 5)),
                listOf(r2[0].first.copy(weight = 1.5f) to r2[0].second) + unitKeys(r2.subList(1, 5))
            ))
            val right = half(listOf(
                unitKeys(r0.subList(5, 10)),
                listOf(spacer(0.5f)) + unitKeys(r1.subList(5, 9)) + spacer(0.5f),
                // Backspace (the last key) stands upright outside the half, where the gap is left.
                listOf(spacer(0.5f)) + unitKeys(r2.subList(5, 8)) + spacer(1.5f)
            ))
            val (backspace, backspaceAddress) = r2.last()

            val bottom = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val bottomItems = rows[3].map { (cfg, address) ->
                when {
                    cfg.action == KeyAction.SPACE -> cfg to address
                    cfg.action == KeyAction.SYMBOLS -> cfg.copy(weight = 1.5f) to address
                    else -> cfg.copy(weight = 1f) to address
                }
            }
            // Bottom row: 123 (1.5) + comma (1) + space + cursor (1) + period (1) spans 8.5 keys plus the gap.
            fillRow(bottom, withCursorKey(bottomItems, spaceWeight = 4f + SPLIT_GAP_UNITS), rowHeight)

            val layout = SplitKeysLayout(context, SPLIT_GAP_UNITS, SPLIT_TILT_DEG, rowHeight, rowGap, margin).apply {
                clipChildren = false
                clipToPadding = false
                setPadding(0, dp(2), 0, 0)
            }
            layout.leftHalf = left
            layout.rightHalf = right
            layout.tallKey = makeKey(backspace, backspaceAddress)
            layout.bottomRow = bottom
            layout.addView(left)
            layout.addView(right)
            layout.addView(layout.tallKey)
            layout.addView(bottom)
            panel.addView(widthWrapped(layout), rowParams())
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
                if (showNumberRow) addRow(withAddresses(KeyboardLayout.NUMBER_ROW, null))
                val rows = letterRows()
                when {
                    !splitStyle -> addRows(rows)
                    wide -> addSplitLetters(rows)
                    else -> addRows(listOf(
                        rows[0],
                        // A half-key inset either side, like a traditional keyboard.
                        listOf(spacer(0.5f)) + rows[1] + spacer(0.5f),
                        rows[2],
                        withCursorKey(rows[3])
                    ))
                }
            }
            KeyboardPage.SYMBOLS_1 -> plainRows(KeyboardLayout.SYMBOLS_1)
            KeyboardPage.SYMBOLS_2 -> plainRows(KeyboardLayout.SYMBOLS_2)
            KeyboardPage.EMOJI -> plainRows(KeyboardLayout.EMOJI_ROWS)
            KeyboardPage.CLIPBOARD -> plainRows(clipboardRows())
        }

        return BuiltKeyboard(
            root, keys, letterKeys, micButton, stripHost, iconStrip, suggestionStrip, suggestionViews,
            separateSuggestions = splitStyle,
            stripEndInset = stripEndInset
        )
    }
}
