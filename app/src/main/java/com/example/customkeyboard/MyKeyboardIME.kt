package com.example.customkeyboard

import android.inputmethodservice.InputMethodService
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout

class MyKeyboardIME : InputMethodService() {

    private var capsOn = false
    private val letterKeys = mutableListOf<KeyView>()
    private val allKeys = mutableListOf<KeyView>()

    private var predictedKeyView: KeyView? = null
    private var activeKeyView: KeyView? = null
    private var downX = 0f
    private var downY = 0f

    private val currentWord = StringBuilder()

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

        for (row in KeyboardLayout.ROWS) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(3); bottomMargin = dp(3) }
            }

            for (keyConfig in row) {
                val keyView = KeyView(
                    context = this,
                    config = keyConfig,
                    onTap = { cfg -> handleTap(cfg) },
                    onFlick = { _, alt -> commitDirect(alt) }
                ).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0, dp(KeyboardLayout.KEY_HEIGHT_DP), keyConfig.weight
                    ).apply { marginStart = dp(2); marginEnd = dp(2) }
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

        root.setOnTouchListener { _, event -> handleTouch(event) }
        return root
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                activeKeyView = resolveKeyAt(event.x.toInt(), event.y.toInt())
                activeKeyView?.setPressedVisual(true)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val target = activeKeyView
                activeKeyView = null
                target?.resolveGesture(event.x - downX, event.y - downY)
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

    private fun resolveKeyAt(x: Int, y: Int): KeyView? {
        predictedKeyView?.let { predicted ->
            if (predicted.absoluteHitRect().contains(x, y)) return predicted
        }
        return allKeys.firstOrNull { it.absoluteHitRect().contains(x, y) }
    }

    private fun handleTap(config: KeyConfig) {
        val ic = currentInputConnection ?: return
        when (config.action) {
            KeyAction.BACKSPACE -> {
                ic.deleteSurroundingText(1, 0)
                if (currentWord.isNotEmpty()) currentWord.deleteCharAt(currentWord.length - 1)
            }
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
                ic.commitText(" ", 1)
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
        }
        updatePrediction()
    }

    private fun commitDirect(text: String) {
        currentInputConnection?.commitText(text, 1)
        currentWord.clear()
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
        predictedKeyView?.isPredicted = false
        predictedKeyView = null
        for (keyView in letterKeys) {
            keyView.displayLabel = keyView.displayLabel.lowercase()
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()
}
