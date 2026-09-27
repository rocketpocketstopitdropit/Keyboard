package com.example.customkeyboard

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import kotlin.math.abs

class MyKeyboardIME : InputMethodService() {

    private var capsOn = false
    private val letterKeys = mutableListOf<KeyView>()
    private val allKeys = mutableListOf<KeyView>()

    private var predictedKeyView: KeyView? = null
    private var activeKeyView: KeyView? = null
    private var downX = 0f
    private var downY = 0f

    private val currentWord = StringBuilder()
    private var pendingCorrection: Pair<String, String>? = null

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.keyboard_background))
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }

        letterKeys.clear()
        allKeys.clear()
        predictedKeyView = null
        activeKeyView = null

        val keyHeight = KeyboardPrefs.getKeyHeight(this)
        val keySpacing = KeyboardPrefs.getKeySpacing(this)
        val showHints = KeyboardPrefs.getShowHints(this)
        val showHighlight = KeyboardPrefs.getShowPredictedHighlight(this)
        val showNumberRow = KeyboardPrefs.getShowNumberRow(this)

        fun buildRow(rowConfigs: List<KeyConfig>) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(3); bottomMargin = dp(3) }
            }
            for (keyConfig in rowConfigs) {
                val keyView = KeyView(
                    context = this,
                    config = keyConfig,
                    onTap = { cfg -> handleTap(cfg) },
                    onFlick = { _, alt -> commitDirect(alt) },
                    showHints = showHints,
                    showPredictedHighlight = showHighlight
                ).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0, dp(keyHeight), keyConfig.weight
                    ).apply { marginStart = dp(keySpacing); marginEnd = dp(keySpacing) }
                }
                rowLayout.addView(keyView)
                allKeys.add(keyView)

                if (keyConfig.action == KeyAction.CHAR && keyConfig.label.length == 1 &&
                    keyConfig.label[0].isLetter()
                ) {
                    letterKeys.add(keyView)
                }
            }
            root.addView(rowLayout)
        }

        if (showNumberRow) buildRow(KeyboardLayout.NUMBER_ROW)

        KeyboardLayout.ROWS.forEachIndexed { rowIndex, row ->
            val effectiveRow = row.mapIndexed { colIndex, defaultConfig ->
                KeyLayoutStore.effectiveConfig(this, rowIndex, colIndex, defaultConfig)
            }
            buildRow(effectiveRow)
        }

        root.setOnTouchListener { _, event -> handleTouch(event) }
        return root
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                activeKeyView = resolveKeyAt(event.x.toInt(), event.y.toInt())
                activeKeyView?.let {
                    it.setPressedVisual(true)
                    vibrateKey()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val target = activeKeyView
                activeKeyView = null
                val dx = event.x - downX
                val dy = event.y - downY
                if (target != null && target.config.action == KeyAction.SPACE &&
                    abs(dx) > abs(dy) && handleSpaceSwipe(dx)
                ) {
                    target.setPressedVisual(false)
                } else {
                    target?.resolveGesture(dx, dy)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                activeKeyView?.setPressedVisual(false)
                activeKeyView = null
                return true
            }
        }
        return false
    }

    private fun handleSpaceSwipe(dx: Float): Boolean {
        val thresholdPx = dp(30).toFloat()
        if (abs(dx) < thresholdPx) return false
        val ic = currentInputConnection ?: return false
        val keyCode = if (dx > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        pendingCorrection = null
        return true
    }

    private fun resolveKeyAt(x: Int, y: Int): KeyView? {
        predictedKeyView?.let { predicted ->
            if (predicted.absoluteHitRect().contains(x, y)) return predicted
        }
        return allKeys.firstOrNull { it.absoluteHitRect().contains(x, y) }
    }

    private fun handleTap(config: KeyConfig) {
        val ic = currentInputConnection ?: return

        if (config.action == KeyAction.BACKSPACE) {
            val pending = pendingCorrection
            pendingCorrection = null
            if (pending != null) {
                val (original, corrected) = pending
                ic.deleteSurroundingText(corrected.length + 1, 0)
                ic.commitText(original, 1)
                currentWord.clear()
                currentWord.append(original)
            } else {
                ic.deleteSurroundingText(1, 0)
                if (currentWord.isNotEmpty()) currentWord.deleteCharAt(currentWord.length - 1)
            }
            updatePrediction()
            return
        }

        pendingCorrection = null

        when (config.action) {
            KeyAction.ENTER -> {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                currentWord.clear()
            }
            KeyAction.SHIFT -> {
                capsOn = !capsOn
                updateCaps()
                return
            }
            KeyAction.SYMBOLS -> {
            }
            KeyAction.SPACE -> {
                val typed = currentWord.toString()
                val correction = Autocorrector.correctionFor(typed)
                if (correction != null) {
                    ic.deleteSurroundingText(typed.length, 0)
                    ic.commitText(correction, 1)
                    ic.commitText(" ", 1)
                    pendingCorrection = typed to correction
                } else {
                    ic.commitText(" ", 1)
                }
                currentWord.clear()
            }
            KeyAction.CHAR -> {
                val text = if (capsOn && config.label.length == 1 && config.label[0].isLetter())
                    config.label.uppercase()
                else config.label
                ic.commitText(text, 1)
                if (config.label.length == 1 && config.label[0].isLetter()) {
                    currentWord.append(config.label)
                } else {
                    currentWord.clear()
                }
            }
            KeyAction.BACKSPACE -> {}
        }
        updatePrediction()
    }

    private fun commitDirect(text: String) {
        currentInputConnection?.commitText(text, 1)
        currentWord.clear()
        pendingCorrection = null
        updatePrediction()
    }

    private fun updatePrediction() {
        predictedKeyView?.isPredicted = false
        predictedKeyView = null

        val guess = LetterPredictor.predictNext(currentWord.toString()) ?: return
        if (guess == ' ') return

        val target = letterKeys.firstOrNull { key ->
            key.config.label.length == 1 && key.config.label.equals(guess.toString(), ignoreCase = true)
        }
        target?.let {
            it.isPredicted = true
            predictedKeyView = it
        }
    }

    private fun updateCaps() {
        for (keyView in letterKeys) {
            keyView.displayLabel =
                if (capsOn) keyView.displayLabel.uppercase() else keyView.displayLabel.lowercase()
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        capsOn = false
        currentWord.clear()
        pendingCorrection = null
        predictedKeyView?.isPredicted = false
        predictedKeyView = null
        for (keyView in letterKeys) {
            keyView.displayLabel = keyView.displayLabel.lowercase()
        }
    }

    private fun vibrateKey() {
        val intensity = KeyboardPrefs.getHapticIntensity(this)
        if (intensity <= 0) return
        val vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator ?: return
        val durationMs = 12L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val amplitude = ((intensity / 100f) * 255).toInt().coerceIn(1, 255)
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()
}
