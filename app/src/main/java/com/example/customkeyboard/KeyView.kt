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

class KeyView(
    context: Context,
    val config: KeyConfig,
    private val onTap: (KeyConfig) -> Unit,
    private val onFlick: (KeyConfig, String) -> Unit,
    private val showHints: Boolean = true,
    private val showPredictedHighlight: Boolean = true
) : FrameLayout(context) {

    companion object {
        const val FLICK_THRESHOLD_DP = 18
        const val PREDICTED_EXPAND_DP = 20
        const val CORNER_RADIUS_DP = 12
        const val GRADIENT_RADIUS_DP = 90
    }

    private val centerLabel: TextView
    private val flickThresholdPx: Float
    private val expandPx: Int

    private val restDrawable: GradientDrawable
    private val pressedDrawable: GradientDrawable

    private var isPressedIn = false

    var isPredicted: Boolean = false
        set(value) {
            field = value
            updateBackground()
        }

    var displayLabel: String = config.label
        set(value) {
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
            mid = Color.parseColor(config.colorHex)
            light = blend(mid, Color.WHITE, 0.35f)
            dark = blend(mid, Color.BLACK, 0.45f)
        } else {
            val baseColorRes = if (config.action != KeyAction.CHAR) R.color.key_background_special
            else R.color.key_background
            mid = context.getColor(baseColorRes)
            light = context.getColor(R.color.key_light)
            dark = context.getColor(R.color.key_dark)
        }
        val cornerPx = dp(CORNER_RADIUS_DP)
        val gradientPx = dp(GRADIENT_RADIUS_DP)

        restDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerPx
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = gradientPx
            setColors(intArrayOf(light, mid, dark))
            setGradientCenter(0.32f, 0.28f)
        }

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
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        addView(centerLabel)

        if (showHints) {
            addCornerHint(config.topLeft, Gravity.TOP or Gravity.START)
            addCornerHint(config.topRight, Gravity.TOP or Gravity.END)
            addCornerHint(config.bottomLeft, Gravity.BOTTOM or Gravity.START)
            addCornerHint(config.bottomRight, Gravity.BOTTOM or Gravity.END)
        }
    }

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
        if (isPredicted && showPredictedHighlight) {
            setBackgroundColor(context.getColor(R.color.key_pressed))
            return
        }
        background = if (isPressedIn) pressedDrawable else restDrawable
    }

    fun localHitRect(): Rect {
        val r = Rect(left, top, right, bottom)
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
