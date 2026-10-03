package com.example.customkeyboard

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.sqrt

/**
 * One key, rendered as a bubble: convex (puffed-out) at rest, concave
 * (dimpled-in) while pressed — done with a radial gradient rather than
 * actual movement, so the key never resizes or shifts, only its shading
 * changes. The character label sits flat on top the whole time.
 *
 * Touch handling/hit-testing happens at the keyboard-container level (see
 * MyKeyboardIME) so a predicted key's expanded hitbox can reach into its
 * neighbors' visual space — this view just reports its own rect and
 * resolves tap-vs-flick once told "you were the target, here's where the
 * finger went."
 */
class KeyView(
    context: Context,
    val config: KeyConfig,
    private val onTap: (KeyConfig) -> Unit,
    private val onFlick: (KeyConfig, String) -> Unit,
    private val showHints: Boolean = true,
    private val showPredictedHighlight: Boolean = true,
    /** Colours for the Split Thumb look; null keeps the original colours. */
    private val palette: KeyPalette? = null,
    /** Fill for this key instead of the palette's (the accent-coloured enter key). */
    private val fillColor: Int? = null,
    private val labelColor: Int? = null,
    /** A small bar under the label (the home keys F and J), or null for none. */
    private val bumpColor: Int? = null
) : FrameLayout(context) {

    companion object {
        const val FLICK_THRESHOLD_DP = 18
        const val PREDICTED_EXPAND_DP = 20
        const val CORNER_RADIUS_DP = 12
        // Fixed gradient radius rather than measuring each key precisely —
        // generous enough to shade every key size we use, simple and cheap.
        const val GRADIENT_RADIUS_DP = 90
    }

    private val centerLabel: TextView
    private val flickThresholdPx: Float
    private val expandPx: Int

    private val restDrawable: GradientDrawable
    private val pressedDrawable: GradientDrawable

    private var isPressedIn = false

    /** Set true on at most one key at a time by the IME. */
    var isPredicted: Boolean = false
        set(value) {
            field = value
            updateBackground()
        }

    var displayLabel: String = config.label
        set(value) {
            // Rewriting identical text still forces a layout pass, which adds
            // up when every key is refreshed after each capital letter.
            if (field == value) return
            field = value
            centerLabel.text = value
        }

    init {
        val dm = resources.displayMetrics
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), dm)

        flickThresholdPx = dp(FLICK_THRESHOLD_DP)
        expandPx = dp(PREDICTED_EXPAND_DP).toInt()

        val mid: Int
        val light: Int
        val dark: Int
        if (config.colorHex != null) {
            // Custom per-key color from the Key Editor — derive light/dark
            // shading from it by blending toward white/black.
            mid = Color.parseColor(config.colorHex)
            light = blend(mid, Color.WHITE, 0.35f)
            dark = blend(mid, Color.BLACK, 0.45f)
        } else if (fillColor != null) {
            mid = fillColor
            light = blend(mid, Color.WHITE, 0.3f)
            dark = blend(mid, Color.BLACK, 0.35f)
        } else if (palette != null) {
            mid = if (config.action != KeyAction.CHAR) palette.special else palette.key
            light = palette.light
            dark = palette.dark
        } else {
            val baseColorRes = if (config.action != KeyAction.CHAR) R.color.key_background_special
            else R.color.key_background
            mid = context.getColor(baseColorRes)
            light = context.getColor(R.color.key_light)
            dark = context.getColor(R.color.key_dark)
        }
        val cornerPx = dp(CORNER_RADIUS_DP)
        val gradientPx = dp(GRADIENT_RADIUS_DP)

        // Convex / resting: bright spot top-left fading toward a dark edge —
        // reads as a bubble puffing UP out of the flat keyboard.
        restDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerPx
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = gradientPx
            setColors(intArrayOf(light, mid, dark))
            setGradientCenter(0.32f, 0.28f)
        }

        // Concave / pressed: dark spot bottom-right with a lighter rim —
        // reads as the bubble dimpling INTO the board.
        pressedDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerPx
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = gradientPx
            setColors(intArrayOf(dark, mid, light))
            setGradientCenter(0.68f, 0.72f)
        }

        background = restDrawable

        centerLabel = TextView(context).apply {
            text = config.label
            setTextColor(labelColor ?: Color.WHITE)
            textSize = if (config.action == KeyAction.CURSOR) 14f else 18f
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        addView(centerLabel)

        if (bumpColor != null) {
            addView(View(context).apply {
                background = GradientDrawable().apply {
                    setColor(bumpColor)
                    cornerRadius = dp(1)
                }
                layoutParams = LayoutParams(dp(10).toInt(), dp(2).toInt(), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                    bottomMargin = dp(7).toInt()
                }
            })
        }

        if (showHints) {
            addCornerHint(config.topLeft, Gravity.TOP or Gravity.START)
            addCornerHint(config.topRight, Gravity.TOP or Gravity.END)
            addCornerHint(config.bottomLeft, Gravity.BOTTOM or Gravity.START)
            addCornerHint(config.bottomRight, Gravity.BOTTOM or Gravity.END)
        }
    }

    /** Blends `color` toward `target` by `ratio` (0 = no change, 1 = fully `target`). */
    private fun blend(color: Int, target: Int, ratio: Float): Int {
        val r = (Color.red(color) * (1 - ratio) + Color.red(target) * ratio).toInt()
        val g = (Color.green(color) * (1 - ratio) + Color.green(target) * ratio).toInt()
        val b = (Color.blue(color) * (1 - ratio) + Color.blue(target) * ratio).toInt()
        return Color.rgb(r, g, b)
    }

    private fun addCornerHint(text: String?, gravity: Int) {
        if (text == null) return
        val tv = TextView(context).apply {
            this.text = text
            setTextColor(Color.parseColor("#999999"))
            textSize = 10f
            this.gravity = gravity
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, gravity
            ).apply {
                val m = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 3f, resources.displayMetrics
                ).toInt()
                setMargins(m, m, m, m)
            }
        }
        addView(tv)
    }

    private fun updateBackground() {
        // The predicted key's expanded hitbox always works regardless of this
        // setting — this only controls whether it's visibly colored differently.
        if (isPredicted && showPredictedHighlight) {
            setBackgroundColor(context.getColor(R.color.key_pressed))
            return
        }
        background = if (isPressedIn) pressedDrawable else restDrawable
    }

    /** How far the predicted key's touch area reaches past its edges, in pixels. */
    val predictedExpandPx: Int get() = expandPx

    /** Never widened — this key's true on-screen bounds. */
    fun rawLocalRect(): Rect = Rect(left, top, right, bottom)

    /** Widened into neighbors when this key is the predicted one. */
    fun localHitRect(): Rect {
        val r = rawLocalRect()
        if (isPredicted) r.inset(-expandPx, -expandPx)
        return r
    }

    fun absoluteHitRect(): Rect {
        val parent = this.parent as View
        val r = localHitRect()
        r.offset(parent.left, parent.top)
        return r
    }

    fun setPressedVisual(pressed: Boolean) {
        isPressedIn = pressed
        updateBackground()
    }

    fun resolveGesture(dx: Float, dy: Float) {
        setPressedVisual(false)
        val distance = sqrt(dx * dx + dy * dy)
        if (distance < flickThresholdPx) {
            onTap(config)
            return
        }
        val corner = when {
            dx >= 0 && dy < 0 -> config.topRight
            dx < 0 && dy < 0 -> config.topLeft
            dx >= 0 && dy >= 0 -> config.bottomRight
            else -> config.bottomLeft
        }
        if (corner != null) onFlick(config, corner) else onTap(config)
    }
}

