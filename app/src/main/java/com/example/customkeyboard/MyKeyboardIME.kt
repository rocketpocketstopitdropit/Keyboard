package com.example.customkeyboard

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import kotlin.math.abs

class MyKeyboardIME : InputMethodService() {

    private enum class ShiftState { OFF, ONCE, LOCKED }

    private var page = KeyboardPage.LETTERS
    private var shiftState = ShiftState.OFF
    private var lastShiftTapTime = 0L

    private lateinit var keyboardContainer: FrameLayout
    private var builtRoot: View? = null
    private val letterKeys = mutableListOf<KeyView>()
    private val allKeys = mutableListOf<KeyView>()

    private var predictedKeyView: KeyView? = null
    private var activeKeyView: KeyView? = null
    private var downX = 0f
    private var downY = 0f

    private val currentWord = StringBuilder()
    private var pendingCorrection: Pair<String, String>? = null

    // Hold-to-repeat (backspace) and space-bar slide state.
    private val uiHandler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null
    private var backspaceHeld = false
    private var spaceSlideActive = false
    private var spaceSlideDir = 0
    private var lastScrubX = 0f

    override fun onCreateInputView(): View {
        keyboardContainer = FrameLayout(this)
        populateKeyboard()
        return keyboardContainer
    }

    /** Rebuilds the keyboard for the current page. Also re-reads settings and key edits. */
    private fun populateKeyboard() {
        stopRepeating()
        keyboardContainer.removeAllViews()
        predictedKeyView = null
        activeKeyView = null

        val built = KeyboardBuilder.build(
            context = this,
            page = page,
            onTap = { cfg -> handleTap(cfg) },
            onFlick = { _, alt -> commitDirect(alt) }
        )
        built.root.setOnTouchListener { _, event -> handleTouch(event) }
        keyboardContainer.addView(
            built.root,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        builtRoot = built.root
        allKeys.clear()
        allKeys.addAll(built.keys)
        letterKeys.clear()
        letterKeys.addAll(built.letterKeys)
        refreshLabels()
    }

    // ---------- Shift ----------

    /** What a key shows and types right now, given the shift state. */
    private fun outputFor(config: KeyConfig): String {
        if (shiftState == ShiftState.OFF) return config.label
        config.shiftLabel?.let { return it }
        return if (config.label.length == 1 && config.label[0].isLetter())
            config.label.uppercase()
        else config.label
    }

    private fun refreshLabels() {
        for (kv in allKeys) {
            val cfg = kv.config
            when (cfg.action) {
                KeyAction.CHAR -> kv.displayLabel = outputFor(cfg)
                KeyAction.SHIFT -> {
                    kv.displayLabel = when (shiftState) {
                        ShiftState.OFF -> cfg.label
                        ShiftState.ONCE -> "⬆"
                        ShiftState.LOCKED -> "⇪"
                    }
                    // Shift stays pressed-in while it's on.
                    kv.setPressedVisual(shiftState != ShiftState.OFF)
                }
                else -> {}
            }
        }
    }

    // ---------- Touch handling ----------

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                spaceSlideActive = false
                spaceSlideDir = 0
                backspaceHeld = false
                val target = resolveKeyAt(event.x.toInt(), event.y.toInt())
                activeKeyView = target
                if (target != null) {
                    target.setPressedVisual(true)
                    vibrateKey()
                    if (target.config.action == KeyAction.BACKSPACE) {
                        // Delete right away, then keep deleting while held.
                        backspaceHeld = true
                        handleTap(target.config)
                        startRepeating(400L, 45L) { handleTap(target.config) }
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val target = activeKeyView
                if (target != null && target.config.action == KeyAction.SPACE) {
                    handleSpaceMove(event.x - downX, event.y - downY, event.x)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val target = activeKeyView
                activeKeyView = null
                stopRepeating()
                when {
                    target == null -> {}
                    backspaceHeld -> target.setPressedVisual(false)
                    spaceSlideActive -> target.setPressedVisual(false)
                    else -> target.resolveGesture(event.x - downX, event.y - downY)
                }
                backspaceHeld = false
                spaceSlideActive = false
                spaceSlideDir = 0
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                stopRepeating()
                activeKeyView?.setPressedVisual(false)
                activeKeyView = null
                backspaceHeld = false
                spaceSlideActive = false
                spaceSlideDir = 0
                return true
            }
        }
        return false
    }

    /**
     * Sliding on the space bar moves the cursor. Every ~16dp of finger travel
     * moves it one character, and holding the finger off to one side keeps
     * moving it in that direction until you let go or slide back to the middle.
     */
    private fun handleSpaceMove(totalDx: Float, totalDy: Float, x: Float) {
        val threshold = dp(24).toFloat()
        val scrubStep = dp(16).toFloat()

        if (!spaceSlideActive) {
            if (abs(totalDx) < threshold || abs(totalDx) < abs(totalDy)) return
            spaceSlideActive = true
            lastScrubX = x
            moveCursor(if (totalDx > 0) 1 else -1)
        }

        while (abs(x - lastScrubX) >= scrubStep) {
            val stepDir = if (x > lastScrubX) 1 else -1
            moveCursor(stepDir)
            lastScrubX += stepDir * scrubStep
        }

        val dir = if (abs(totalDx) >= threshold) (if (totalDx > 0) 1 else -1) else 0
        if (dir != spaceSlideDir) {
            spaceSlideDir = dir
            if (dir == 0) stopRepeating()
            else startRepeating(350L, 70L) { moveCursor(spaceSlideDir) }
        }
    }

    private fun moveCursor(dir: Int) {
        val ic = currentInputConnection ?: return
        val keyCode = if (dir > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        currentWord.clear()
        pendingCorrection = null
        updatePrediction()
    }

    private fun startRepeating(initialDelayMs: Long, intervalMs: Long, action: () -> Unit) {
        stopRepeating()
        val r = object : Runnable {
            override fun run() {
                action()
                uiHandler.postDelayed(this, intervalMs)
            }
        }
        repeatRunnable = r
        uiHandler.postDelayed(r, initialDelayMs)
    }

    private fun stopRepeating() {
        repeatRunnable?.let { uiHandler.removeCallbacks(it) }
        repeatRunnable = null
    }

    private fun resolveKeyAt(x: Int, y: Int): KeyView? {
        val root = builtRoot ?: return null
        predictedKeyView?.let { predicted ->
            if (predicted.hitRectIn(root).contains(x, y)) return predicted
        }
        return allKeys.firstOrNull { it.hitRectIn(root).contains(x, y) }
    }

    // ---------- Key actions ----------

    private fun matchCase(typed: String, correction: String): String = when {
        typed.length > 1 && typed.all { it.isUpperCase() } -> correction.uppercase()
        typed.isNotEmpty() && typed[0].isUpperCase() -> correction.replaceFirstChar { it.uppercase() }
        else -> correction
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
                val selected = ic.getSelectedText(0)
                if (!selected.isNullOrEmpty()) {
                    ic.commitText("", 1)
                    currentWord.clear()
                } else {
                    ic.deleteSurroundingText(1, 0)
                    if (currentWord.isNotEmpty()) currentWord.deleteCharAt(currentWord.length - 1)
                }
            }
            updatePrediction()
            return
        }

        pendingCorrection = null

        when (config.action) {
            KeyAction.ENTER -> {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                currentWord.clear()
            }
            KeyAction.SHIFT -> {
                // Tap once = capitalize the next letter. Tap twice quickly = caps lock.
                val now = SystemClock.uptimeMillis()
                shiftState = when {
                    shiftState == ShiftState.LOCKED -> ShiftState.OFF
                    shiftState == ShiftState.ONCE && now - lastShiftTapTime < 400L -> ShiftState.LOCKED
                    shiftState == ShiftState.ONCE -> ShiftState.OFF
                    else -> ShiftState.ONCE
                }
                lastShiftTapTime = now
                refreshLabels()
                return
            }
            KeyAction.SYMBOLS -> {
                page = KeyboardPage.SYMBOLS_1
                shiftState = ShiftState.OFF
                currentWord.clear()
                populateKeyboard()
                return
            }
            KeyAction.SYMBOLS_ALT -> {
                page = if (page == KeyboardPage.SYMBOLS_1) KeyboardPage.SYMBOLS_2 else KeyboardPage.SYMBOLS_1
                currentWord.clear()
                populateKeyboard()
                return
            }
            KeyAction.LETTERS -> {
                page = KeyboardPage.LETTERS
                currentWord.clear()
                populateKeyboard()
                return
            }
            KeyAction.SPACE -> {
                val typed = currentWord.toString()
                val correction = Autocorrector.correctionFor(typed)?.let { matchCase(typed, it) }
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
                val text = outputFor(config)
                ic.commitText(text, 1)
                if (text.length == 1 && text[0].isLetter()) {
                    currentWord.append(text)
                } else {
                    currentWord.clear()
                }
                if (shiftState == ShiftState.ONCE) {
                    shiftState = ShiftState.OFF
                    refreshLabels()
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

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        currentWord.clear()
        pendingCorrection = null
        if (!restarting) {
            shiftState = ShiftState.OFF
            page = KeyboardPage.LETTERS
        }
        // Rebuild so settings and key edits apply every time the keyboard opens.
        if (::keyboardContainer.isInitialized) populateKeyboard()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        stopRepeating()
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
