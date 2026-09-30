package com.example.customkeyboard

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.content.ClipDescription
import android.graphics.Color
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import java.io.File
import android.graphics.Typeface
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlin.math.abs

class MyKeyboardIME : InputMethodService() {

    private enum class ShiftState { OFF, ONCE, LOCKED }

    private var page = KeyboardPage.LETTERS
    private var shiftState = ShiftState.OFF
    private var lastShiftTapTime = 0L

    private lateinit var keyboardContainer: FrameLayout
    private var builtRoot: View? = null
    private var micButton: TextView? = null
    private val letterKeys = mutableListOf<KeyView>()
    private val allKeys = mutableListOf<KeyView>()

    private var iconStrip: View? = null
    private var suggestionStrip: View? = null
    private var suggestionViews: List<TextView> = emptyList()

    private var predictedKeyView: KeyView? = null
    private var activeKeyView: KeyView? = null
    private var downX = 0f
    private var downY = 0f

    private val currentWord = StringBuilder()
    private var previousWord: String = WordPredictor.START
    private var pendingCorrection: Pair<String, String>? = null
    private var pendingCorrectionPrev: String = ""
    private var autocorrectSuppressedWord: String? = null
    private var learningAllowed = true
    private var autocorrectAllowed = true

    private val uiHandler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null
    private var backspaceHeld = false
    private var spaceSlideActive = false
    private var spaceSlideDir = 0
    private var lastScrubX = 0f

    private val vibrator: Vibrator? by lazy { getSystemService(VIBRATOR_SERVICE) as? Vibrator }
    private val clipboardManager: ClipboardManager by lazy {
        getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
    }
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        val clip = clipboardManager.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this)?.toString() else null
        if (!text.isNullOrBlank()) ClipboardHistoryStore.add(this, text)
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var listening = false

    override fun onCreate() {
        super.onCreate()
        WordPredictor.init(this)
        clipboardManager.addPrimaryClipChangedListener(clipListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        clipboardManager.removePrimaryClipChangedListener(clipListener)
        speechRecognizer?.destroy()
    }

    override fun onCreateInputView(): View {
        keyboardContainer = FrameLayout(this)
        populateKeyboard()
        return keyboardContainer
    }

    private fun populateKeyboard() {
        stopRepeating()
        keyboardContainer.removeAllViews()
        predictedKeyView = null
        activeKeyView = null

        val built = KeyboardBuilder.build(
            context = this,
            page = page,
            clipboardHistory = ClipboardHistoryStore.getAll(this),
            onTap = { cfg -> handleTap(cfg) },
            onFlick = { _, alt -> commitDirect(alt) },
            onAccessory = { action -> handleAccessory(action) },
            onSuggestion = { word -> onSuggestionTapped(word) }
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
        micButton = built.micButton
        iconStrip = built.iconStrip
        suggestionStrip = built.suggestionStrip
        suggestionViews = built.suggestionViews
        if (gifMode) attachGifPanel(built)
        allKeys.clear()
        allKeys.addAll(built.keys)
        letterKeys.clear()
        letterKeys.addAll(built.letterKeys)
        refreshLabels()
    }

    // ---------- GIF search ----------

    private var gifMode = false
    private val gifQuery = StringBuilder()
    private var gifQueryView: TextView? = null
    private var gifStatusView: TextView? = null
    private var gifResultsRow: LinearLayout? = null
    private var gifSearchToken = 0
    private val gifSearchRunnable = Runnable { runGifSearch() }

    private fun enterGifMode() {
        if (KeyboardPrefs.getKlipyKey(this).isBlank()) {
            Toast.makeText(
                this,
                "Add your Klipy API key first: tap the gear, then scroll to GIF search.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        page = KeyboardPage.LETTERS
        currentWord.clear()
        pendingCorrection = null
        gifQuery.setLength(0)
        gifMode = true
        populateKeyboard()
        runGifSearch()
    }

    private fun exitGifMode() {
        if (!gifMode) return
        stopGifMode()
        if (::keyboardContainer.isInitialized) populateKeyboard()
    }

    /** Turn GIF mode off without rebuilding the keyboard (the caller does that). */
    private fun stopGifMode() {
        gifMode = false
        gifQuery.setLength(0)
        gifSearchToken++
        uiHandler.removeCallbacks(gifSearchRunnable)
    }

    /** Swap the hotkeys for a search box, and put a row of results above the keys. */
    private fun attachGifPanel(built: BuiltKeyboard) {
        built.iconStrip.visibility = View.GONE
        built.suggestionStrip.visibility = View.GONE

        val queryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(2), 0)
        }
        queryRow.addView(TextView(this).apply {
            text = "\u2715"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(36))
            setOnClickListener { exitGifMode() }
        })
        val queryView = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f)
        }
        queryRow.addView(queryView)
        queryRow.addView(TextView(this).apply {
            text = "Powered by KLIPY"
            textSize = 10f
            setTextColor(Color.LTGRAY)
            setPadding(dp(4), 0, dp(4), 0)
        })
        built.stripHost.addView(
            queryRow,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
        val status = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }
        val holder = FrameLayout(this)
        holder.setBackgroundColor(getColor(R.color.keyboard_background))
        holder.addView(
            scroll,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        holder.addView(
            status,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        // Index 1 = between the top strip and the key rows.
        built.root.addView(holder, 1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(112)))

        gifQueryView = queryView
        gifStatusView = status
        gifResultsRow = row
        updateGifQueryView()
    }

    private fun updateGifQueryView() {
        gifQueryView?.text = if (gifQuery.isEmpty()) {
            "\uD83D\uDD0D  Type to search GIFs"
        } else {
            "\uD83D\uDD0D  ${gifQuery}\u258F"
        }
    }

    private fun showGifStatus(message: String) {
        gifResultsRow?.removeAllViews()
        gifStatusView?.text = message
        gifStatusView?.visibility = View.VISIBLE
    }

    private fun scheduleGifSearch() {
        updateGifQueryView()
        uiHandler.removeCallbacks(gifSearchRunnable)
        uiHandler.postDelayed(gifSearchRunnable, 450)
    }

    private fun runGifSearch() {
        uiHandler.removeCallbacks(gifSearchRunnable)
        val key = KeyboardPrefs.getKlipyKey(this).trim()
        val query = gifQuery.toString().trim()
        val token = ++gifSearchToken
        showGifStatus(if (query.isEmpty()) "Loading trending GIFs\u2026" else "Searching\u2026")
        GifClient.runAsync {
            try {
                val results = GifClient.fetchResults(key, query)
                uiHandler.post { if (token == gifSearchToken && gifMode) showGifResults(results, token) }
            } catch (e: Exception) {
                val reason = e.message ?: "unknown error"
                uiHandler.post {
                    if (token == gifSearchToken && gifMode) showGifStatus("Couldn't load GIFs ($reason)")
                }
            }
        }
    }

    private fun showGifResults(results: List<GifResult>, token: Int) {
        val row = gifResultsRow ?: return
        row.removeAllViews()
        (row.parent as? HorizontalScrollView)?.scrollTo(0, 0)
        if (results.isEmpty()) {
            showGifStatus("No GIFs found")
            return
        }
        gifStatusView?.visibility = View.GONE
        for (gif in results) {
            val cell = ImageView(this)
            cell.scaleType = ImageView.ScaleType.CENTER_CROP
            cell.setBackgroundColor(Color.argb(60, 255, 255, 255))
            cell.setOnClickListener { sendGif(gif) }
            val lp = LinearLayout.LayoutParams(dp(120), dp(96))
            lp.marginEnd = dp(6)
            row.addView(cell, lp)
            GifClient.runAsync {
                val bitmap = GifClient.loadBitmap(gif.previewUrl, dp(240))
                if (bitmap != null) {
                    uiHandler.post { if (token == gifSearchToken && gifMode) cell.setImageBitmap(bitmap) }
                }
            }
        }
    }

    /** In GIF mode the letter keys type into the search box, not into the app. */
    private fun handleGifKey(config: KeyConfig): Boolean {
        return when (config.action) {
            KeyAction.CHAR -> {
                val text = config.commitOverride ?: outputFor(config)
                if (text.length == 1) {
                    gifQuery.append(text.lowercase())
                    if (shiftState == ShiftState.ONCE) {
                        shiftState = ShiftState.OFF
                        refreshLabels()
                    }
                    scheduleGifSearch()
                }
                true
            }
            KeyAction.SPACE -> {
                if (gifQuery.isNotEmpty() && gifQuery.last() != ' ') gifQuery.append(' ')
                scheduleGifSearch()
                true
            }
            KeyAction.BACKSPACE -> {
                if (gifQuery.isEmpty()) {
                    exitGifMode()
                } else {
                    gifQuery.deleteCharAt(gifQuery.length - 1)
                    scheduleGifSearch()
                }
                true
            }
            KeyAction.ENTER -> {
                updateGifQueryView()
                runGifSearch()
                true
            }
            KeyAction.SYMBOLS, KeyAction.SYMBOLS_ALT, KeyAction.LETTERS -> {
                // Switching pages leaves GIF mode; the normal handler rebuilds the keyboard.
                stopGifMode()
                false
            }
            else -> false
        }
    }

    private fun sendGif(gif: GifResult) {
        Toast.makeText(this, "Sending GIF\u2026", Toast.LENGTH_SHORT).show()
        GifClient.runAsync {
            try {
                val dir = File(cacheDir, "gifs")
                dir.mkdirs()
                val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
                dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
                val ext = when (gif.mime) {
                    "image/webp" -> "webp"
                    "image/png" -> "png"
                    "image/jpeg" -> "jpg"
                    else -> "gif"
                }
                val file = File(dir, "gif_${System.currentTimeMillis()}.$ext")
                GifClient.downloadTo(gif.sendUrl, file)
                uiHandler.post { commitGif(file, gif) }
            } catch (e: Exception) {
                uiHandler.post {
                    Toast.makeText(this, "Couldn't download that GIF.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Hand the GIF to the app if it accepts images; otherwise paste the link. */
    private fun commitGif(file: File, gif: GifResult) {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo ?: return
        val accepted = EditorInfoCompat.getContentMimeTypes(info)
        val supported = accepted.any { ClipDescription.compareMimeTypes(gif.mime, it) }
        var sent = false
        if (supported) {
            try {
                val uri = FileProvider.getUriForFile(this, "$packageName.gifs", file)
                val content = InputContentInfoCompat(uri, ClipDescription("GIF", arrayOf(gif.mime)), null)
                val flags = if (Build.VERSION.SDK_INT >= 25) {
                    InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION
                } else {
                    0
                }
                sent = InputConnectionCompat.commitContent(ic, info, content, flags, null)
            } catch (e: Exception) {
                sent = false
            }
        }
        if (!sent) {
            ic.commitText(gif.sendUrl + " ", 1)
            Toast.makeText(this, "This app can't take GIFs directly, so the link was pasted.", Toast.LENGTH_LONG).show()
        }
        exitGifMode()
    }

    // ---------- Accessory row ----------

    private fun handleAccessory(action: AccessoryAction) {
        when (action) {
            AccessoryAction.SETTINGS -> {
                startActivity(
                    Intent(this, KeyEditorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            AccessoryAction.GIF -> {
                if (gifMode) exitGifMode() else enterGifMode()
            }
            AccessoryAction.EMOJI -> {
                page = if (page == KeyboardPage.EMOJI) KeyboardPage.LETTERS else KeyboardPage.EMOJI
                currentWord.clear()
                populateKeyboard()
            }
            AccessoryAction.CLIPBOARD -> {
                page = if (page == KeyboardPage.CLIPBOARD) KeyboardPage.LETTERS else KeyboardPage.CLIPBOARD
                currentWord.clear()
                populateKeyboard()
            }
            AccessoryAction.MIC -> onMicTapped()
        }
    }

    private fun onMicTapped() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            startActivity(Intent(this, PermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Toast.makeText(this, "Allow microphone access, then tap the mic again.", Toast.LENGTH_LONG).show()
            return
        }
        if (listening) {
            stopListening()
        } else {
            startListening()
        }
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition isn't available on this device.", Toast.LENGTH_SHORT).show()
            return
        }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer = recognizer
        listening = true
        micButton?.text = "⏺"

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.replaceFirstChar { it.uppercase() }
                if (!text.isNullOrBlank()) currentInputConnection?.commitText("$text ", 1)
                stopListening()
            }
            override fun onError(error: Int) {
                stopListening()
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer.startListening(intent)
    }

    private fun stopListening() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        listening = false
        micButton?.text = "🎤"
    }

    // ---------- Shift ----------

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
        previousWord = ""
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
        allKeys.firstOrNull { it.rawHitRectIn(root).contains(x, y) }?.let { return it }
        predictedKeyView?.let { predicted ->
            if (predicted.hitRectIn(root).contains(x, y)) return predicted
        }
        return null
    }

    // ---------- Key actions ----------

    private fun matchCase(typed: String, correction: String): String = when {
        typed.length > 1 && typed.all { it.isUpperCase() } -> correction.uppercase()
        typed.isNotEmpty() && typed[0].isUpperCase() -> correction.replaceFirstChar { it.uppercase() }
        else -> correction
    }

    private fun handleTap(config: KeyConfig) {
        if (gifMode && handleGifKey(config)) return
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
                autocorrectSuppressedWord = original
                // Undo what was learned from the correction, and go back to the
                // word that came before the one being retyped.
                if (learningAllowed) WordPredictor.unlearn(pendingCorrectionPrev, corrected)
                previousWord = pendingCorrectionPrev
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
                finishWord(currentWord.toString(), 1f)
                previousWord = WordPredictor.START
            }
            KeyAction.SHIFT -> {
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
                val suppressed = autocorrectSuppressedWord
                autocorrectSuppressedWord = null
                // The person just undid a correction for this exact word: leave it
                // alone now, and remember it strongly so it isn't "fixed" again.
                val rejected = typed.isNotEmpty() && typed == suppressed
                val endsWithApostrophe = typed.isNotEmpty() && isApostrophe(typed.last())
                val correction = if (typed.isEmpty() || rejected || endsWithApostrophe || !autocorrectAllowed) {
                    null
                } else {
                    Autocorrector.correctionFor(typed, previousWord)?.let { matchCase(typed, it) }
                }
                if (correction != null) {
                    ic.deleteSurroundingText(typed.length, 0)
                    ic.commitText(correction, 1)
                    ic.commitText(" ", 1)
                    pendingCorrection = typed to correction
                    pendingCorrectionPrev = previousWord
                } else {
                    ic.commitText(" ", 1)
                }
                finishWord(correction ?: typed, if (rejected) 3f else 1f)
            }
            KeyAction.CHAR -> {
                val text = config.commitOverride ?: outputFor(config)
                ic.commitText(text, 1)
                val isLetter = text.length == 1 && text[0].isLetter()
                // An apostrophe inside a word ("don't") belongs to the word.
                val isInnerApostrophe = text.length == 1 && isApostrophe(text[0]) && currentWord.isNotEmpty()
                if (isLetter || isInnerApostrophe) {
                    currentWord.append(text)
                } else {
                    finishWord(currentWord.toString(), 1f)
                    if (text.length == 1 && (text[0] == '.' || text[0] == '!' || text[0] == '?')) {
                        previousWord = WordPredictor.START
                    }
                }
                if (shiftState == ShiftState.ONCE) {
                    shiftState = ShiftState.OFF
                    refreshLabels()
                }
            }
            KeyAction.BACKSPACE, KeyAction.SPACER -> {}
        }
        updatePrediction()
    }

    private fun commitDirect(text: String) {
        if (gifMode) {
            if (text.length == 1) {
                gifQuery.append(text.lowercase())
                scheduleGifSearch()
            }
            return
        }
        currentInputConnection?.commitText(text, 1)
        finishWord(currentWord.toString(), 1f)
        pendingCorrection = null
        autocorrectSuppressedWord = null
        updatePrediction()
    }

    private fun isApostrophe(c: Char): Boolean = c == '\'' || c == '\u2019'

    /**
     * The word just ended: learn it (after the previous word), make it the
     * new previous word, and start a fresh current word.
     */
    private fun finishWord(word: String, weight: Float) {
        val bare = word.trimEnd('\'', '\u2019')
        if (bare.isNotEmpty()) {
            if (learningAllowed) WordPredictor.learn(previousWord, bare, weight)
            previousWord = WordPredictor.normalize(bare)
        }
        currentWord.clear()
    }

    /**
     * Decide, per text field, whether to learn from typing and whether to
     * autocorrect. Passwords are never learned or corrected; email, URL,
     * search-filter and name fields are never autocorrected; fields that ask
     * for no personalised learning (incognito modes) are respected.
     */
    private fun applyFieldPolicy(info: EditorInfo?) {
        val type = info?.inputType ?: 0
        val typeClass = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        val isText = typeClass == InputType.TYPE_CLASS_TEXT

        val isPassword = (isText && (
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
                (typeClass == InputType.TYPE_CLASS_NUMBER &&
                        variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        val optedOut = ((info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        val isStructured = isText && (
                variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_URI ||
                variation == InputType.TYPE_TEXT_VARIATION_FILTER ||
                variation == InputType.TYPE_TEXT_VARIATION_PERSON_NAME)
        val noSuggestions = isText && (type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0

        learningAllowed = isText && !isPassword && !optedOut && KeyboardPrefs.getLearnWords(this)
        autocorrectAllowed = isText && !isPassword && !isStructured && !noSuggestions
    }

    /**
     * Hotkeys when idle; three word suggestions while a word is being typed.
     * The best guess goes in the middle. If the word being typed will be
     * autocorrected, the correction is the best guess.
     */
    private fun updateSuggestions() {
        if (gifMode) return
        val icons = iconStrip ?: return
        val strip = suggestionStrip ?: return
        val typed = currentWord.toString()

        if (typed.isEmpty() || !autocorrectAllowed || suggestionViews.size < 3) {
            strip.visibility = View.GONE
            icons.visibility = View.VISIBLE
            return
        }

        val words = ArrayList<String>()
        Autocorrector.correctionFor(typed, previousWord)?.let { words.add(matchCase(typed, it)) }
        for (w in WordPredictor.suggestions(previousWord, typed, 3)) {
            val shown = matchCase(typed, w)
            if (words.none { it.equals(shown, ignoreCase = true) }) words.add(shown)
        }
        if (words.isEmpty()) words.add(typed)
        val top = words.take(3)

        // Slots are left, centre, right; the best guess takes the centre.
        val slots = arrayOf(top.getOrNull(1), top.getOrNull(0), top.getOrNull(2))
        for (i in 0 until 3) {
            val tv = suggestionViews[i]
            tv.text = slots[i] ?: ""
            tv.setTypeface(null, if (i == 1) Typeface.BOLD else Typeface.NORMAL)
        }
        icons.visibility = View.GONE
        strip.visibility = View.VISIBLE
    }

    /** Replace the word being typed with a tapped suggestion, then add a space. */
    private fun onSuggestionTapped(word: String) {
        val ic = currentInputConnection ?: return
        val typed = currentWord.toString()
        if (typed.isEmpty() || word.isEmpty()) return

        ic.deleteSurroundingText(typed.length, 0)
        ic.commitText("$word ", 1)
        // Backspace right after undoes the swap, just like an autocorrection.
        pendingCorrection = if (word != typed) typed to word else null
        pendingCorrectionPrev = previousWord
        autocorrectSuppressedWord = null
        finishWord(word, 1f)
        updatePrediction()
    }

    private fun updatePrediction() {
        updateSuggestions()
        predictedKeyView?.isPredicted = false
        predictedKeyView = null

        val guess = LetterPredictor.predictNext(previousWord, currentWord.toString()) ?: return
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
        exitGifMode()
        currentWord.clear()
        pendingCorrection = null
        autocorrectSuppressedWord = null
        previousWord = WordPredictor.START
        applyFieldPolicy(info)
        if (!restarting) {
            shiftState = ShiftState.OFF
            page = KeyboardPage.LETTERS
            if (::keyboardContainer.isInitialized) populateKeyboard()
        }
        updateSuggestions()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        stopRepeating()
        stopListening()
        WordPredictor.flush()
    }

    private fun vibrateKey() {
        val intensity = KeyboardPrefs.getHapticIntensity(this)
        if (intensity <= 0) return
        val v = vibrator ?: return
        val durationMs = 12L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val amplitude = ((intensity / 100f) * 255).toInt().coerceIn(1, 255)
            v.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(durationMs)
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()
}

