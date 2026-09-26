package com.example.customkeyboard

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
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
    private val onFlick: (KeyConfig, String) -> Unit
) : FrameLayout(context) {

    companion object {
        const val FLICK_THRESHOLD_DP = 18
        const val PREDICTED_EXPAND_DP = 20
    }

    private val centerLabel: TextView
    private val flickThresholdPx: Float
    private val expandPx: Int

    var isPredicted: Boolean = false
        set(value) {
            field = value
            setBackgroundColor(
                context.getColor(
                    when {
                        value -> R.color.key_pressed
                        config.action != KeyAction.CHAR -> R.color.key_background_special
                        else -> R.color.key_background
                    }
                )
            )
        }

    var displayLabel: String = config.label
        set(value) {
            field = value
            centerLabel.text = value
        }

    init {
        flickThresholdPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, FLICK_THRESHOLD_DP.toFloat(), resources.displayMetrics
        )
        expandPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, PREDICTED_EXPAND_DP.toFloat(), resources.displayMetrics
        ).toInt()

        setBackgroundColor(
            context.getColor(
                if (config.action != KeyAction.CHAR) R.color.key_background_special
                else R.color.key_background
            )
        )

        centerLabel = TextView(context).apply {
            text = config.label
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        addView(centerLabel)

        addCornerHint(config.topLeft, Gravity.TOP or Gravity.START)
        addCornerHint(config.topRight, Gravity.TOP or Gravity.END)
        addCornerHint(config.bottomLeft, Gravity.BOTTOM or Gravity.START)
        addCornerHint(config.bottomRight, Gravity.BOTTOM or Gravity.END)
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
        isPressed = pressed
    }

    fun resolveGesture(dx: Float, dy: Float) {
        isPressed = false
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
