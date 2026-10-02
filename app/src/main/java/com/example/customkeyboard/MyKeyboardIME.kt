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
import android.graphics.Rect
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.inputmethod.InputConnection
import kotlin.math.ln
import android.os.SystemClock
import android.util.SparseArray
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

// How the touch model weighs where a finger landed against what the word model expects next.
private const val TOUCH_SIGMA = 0.4
private const val MAX_TOUCH_D2 = 1.0
private const val PRIOR_FLOOR = 0.02

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

    private val currentWord = StringBuilder()
    private var previousWord: String = WordPredictor.START
    private var pendingCorrection: Pair<String, String>? = null
    private var pendingCorrectionPrev: String = ""
    private var autocorrectSuppressedWord: String? = null
    private var learningAllowed = true
    private var autocorrectAllowed = true

    private val uiHandler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null

    // Double-space period and auto-capitalization state.
    private var spaceAfterWord = false
    private var lastWasSpace = false
    private var lastSpaceTime = 0L
    private var lastCharWordish = false
    private var sentenceEndPending = false
    private var shiftIsAuto = false
    /** The person switched off an automatic capital; don't force one on the next letter. */
    private var autoCapCancelled = false
    private var autoCapAllowed = true
    private var autoPeriodAllowed = true
    private val doubleSpaceMs = 700L

    // Where the selection is, kept up to date by the system so backspace never has to ask the app.
    private var selStart = 0
    private var selEnd = 0

    // Predictions can wait for a pause in typing; the keys can't.
    private val predictionRunnable = Runnable { updatePredictionNow() }
    private val predictionDelayMs = 40L

    // Learned counts are saved during a pause in typing, never between keystrokes.
    private val flushRunnable = Runnable { WordPredictor.flush() }

    private fun scheduleFlush() {
        uiHandler.removeCallbacks(flushRunnable)
        uiHandler.postDelayed(flushRunnable, 4000L)
    }

    // How strongly the model's guess about the next letter widens that key (0-100).
    private var predictionStrength = 50
    private var letterPrior: Map<Char, Double> = emptyMap()
    private var letterPriorMax = 0.0

    // The word that a flick up on the space bar accepts (the centre suggestion).
    private var acceptWord: String? = null

    // The autocorrect answer for the word being typed, so pressing space doesn't redo the search.
    private var corrTyped: String? = null
    private var corrPrev = ""
    private var corrResult: String? = null

    private fun correctionFor(typed: String): String? {
        if (corrTyped == typed && corrPrev == previousWord) return corrResult
        val result = Autocorrector.correctionFor(typed, previousWord)
        corrTyped = typed
        corrPrev = previousWord
        corrResult = result
        return result
    }

    private var hapticLevel = -1
    private var hapticEffect: Any? = null

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
        cancelAllTouches()
        keyRects = null

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
        built.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> keyRects = null }
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
        refreshLetterPrior()
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

    /** What one finger is doing. Fingers are tracked separately so overlapping taps in fast typing all count. */
    private class TouchState(val key: KeyView?, val downX: Float, val downY: Float, val seq: Long) {
        /** Where the finger is now (for typing this key early, if a later finger lifts first). */
        var lastX = downX
        var lastY = downY
        /** Space bar only: -1 / +1 once it has become a cursor gesture. */
        var slideDir = 0
        var spaceGesture = false
        var backspace = false
        /** The gesture already did its job (a flick up accepted a suggestion). */
        var consumed = false
    }

    private class KeyRect(val key: KeyView, val rect: Rect)
    private class LetterRect(val key: KeyView, val rect: Rect, val letter: Char)

    private val touches = SparseArray<TouchState>()
    private var keyRects: List<KeyRect>? = null
    private var letterRects: List<LetterRect> = emptyList()
    private var repeatOwner = -1
    private var holdRunnable: Runnable? = null
    private var touchSeq = 0L

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                pointerDown(event.getPointerId(i), event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    pointerMove(event.getPointerId(i), event.getX(i), event.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                pointerUp(event.getPointerId(i), event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_CANCEL -> cancelAllTouches()
        }
        return true
    }

    /** Letter keys still held down, oldest first: typed, but not yet committed. */
    private fun heldLetterTouches(): List<TouchState> {
        val held = ArrayList<TouchState>()
        for (i in 0 until touches.size()) {
            val t = touches.valueAt(i)
            val k = t.key ?: continue
            if (!t.consumed && k.config.action == KeyAction.CHAR) held.add(t)
        }
        held.sortBy { it.seq }
        return held
    }

    private fun pointerDown(id: Int, x: Float, y: Float) {
        // In fast typing the next finger lands before the last one lifts, so that
        // last letter isn't typed yet. Judge this touch as if it were: after "a",
        // N should win over B because of "and", not because of how words start.
        val held = heldLetterTouches()
        val target = if (held.isEmpty()) {
            resolveKeyAt(x.toInt(), y.toInt(), letterPrior, letterPriorMax)
        } else {
            val pending = StringBuilder(currentWord)
            var wordEnded = false
            for (t in held) {
                val c = letterOf(t.key!!.config)
                if (c == null) wordEnded = true else pending.append(c)
            }
            val prior = if (wordEnded || !priorActive()) emptyMap()
            else WordPredictor.letterDistribution(previousWord, pending.toString())
            resolveKeyAt(x.toInt(), y.toInt(), prior, prior.values.maxOrNull() ?: 0.0)
        }
        val state = TouchState(target, x, y, ++touchSeq)
        touches.put(id, state)
        if (target == null) return
        target.setPressedVisual(true)
        vibrateKey()
        when (target.config.action) {
            KeyAction.BACKSPACE -> {
                state.backspace = true
                handleTap(target.config)
                startRepeating(400L, 45L) { handleTap(target.config) }
                repeatOwner = id
            }
            KeyAction.SPACE -> scheduleSpaceHold(id)
            else -> {}
        }
    }

    private fun pointerMove(id: Int, x: Float, y: Float) {
        val state = touches.get(id) ?: return
        state.lastX = x
        state.lastY = y
        val key = state.key ?: return
        if (key.config.action != KeyAction.SPACE || state.spaceGesture) return
        val dx = x - state.downX
        val dy = y - state.downY
        // A flick up on the space bar accepts the dimmed prediction in the suggestion bar.
        if (dy <= -dp(24) && abs(dy) > abs(dx)) {
            val word = acceptWord
            if (word != null) {
                state.spaceGesture = true
                state.consumed = true
                holdRunnable?.let { uiHandler.removeCallbacks(it) }
                holdRunnable = null
                onSuggestionTapped(word)
            }
            return
        }
        // A flick along the space bar moves the cursor exactly one character.
        if (abs(dx) >= dp(24) && abs(dx) > abs(dy)) {
            state.slideDir = if (dx > 0) 1 else -1
            state.spaceGesture = true
            moveCursor(state.slideDir)
            // Keep holding after the flick and it keeps going, getting faster.
            scheduleSpaceHold(id)
        }
    }

    private fun pointerUp(id: Int, x: Float, y: Float) {
        val state = touches.get(id) ?: return
        touches.remove(id)
        if (repeatOwner == id) {
            stopRepeating()
            repeatOwner = -1
        }
        holdRunnable?.let { uiHandler.removeCallbacks(it) }
        holdRunnable = null
        val key = state.key ?: return
        // A finger that landed earlier but is still down gets typed first, so
        // letters always come out in the order they were pressed ("an", not "na").
        if (!state.backspace && !state.consumed && !state.spaceGesture) {
            for (t in heldLetterTouches()) {
                if (t.seq >= state.seq) break
                t.consumed = true
                t.key?.resolveGesture(t.lastX - t.downX, t.lastY - t.downY)
            }
        }
        when {
            state.backspace -> key.setPressedVisual(false)
            state.consumed -> key.setPressedVisual(false)
            state.spaceGesture -> {
                key.setPressedVisual(false)
                refreshAutoCap()
            }
            else -> key.resolveGesture(x - state.downX, y - state.downY)
        }
    }

    private fun cancelAllTouches() {
        stopRepeating()
        repeatOwner = -1
        holdRunnable?.let { uiHandler.removeCallbacks(it) }
        holdRunnable = null
        for (i in 0 until touches.size()) touches.valueAt(i).key?.setPressedVisual(false)
        touches.clear()
    }

    /** After a short hold on the space bar, start moving the cursor continuously. */
    private fun scheduleSpaceHold(id: Int) {
        holdRunnable?.let { uiHandler.removeCallbacks(it) }
        val r = Runnable { beginSpaceHold(id) }
        holdRunnable = r
        uiHandler.postDelayed(r, 400L)
    }

    private fun beginSpaceHold(id: Int) {
        val state = touches.get(id) ?: return
        if (state.consumed) return
        // After a flick the hold continues that way; otherwise it goes toward
        // whichever end of the space bar the finger is on (the middle stays a space).
        val dir = if (state.slideDir != 0) state.slideDir else spaceSide(state)
        if (dir == 0) return
        state.spaceGesture = true
        state.slideDir = dir
        startCursorRepeat(dir)
        repeatOwner = id
    }

    private fun spaceSide(state: TouchState): Int {
        val root = builtRoot ?: return 0
        val key = state.key ?: return 0
        val rect = key.rawHitRectIn(root)
        if (rect.width() <= 0) return 0
        val fraction = (state.downX - rect.left) / rect.width().toFloat()
        return when {
            fraction < 0.3f -> -1
            fraction > 0.7f -> 1
            else -> 0
        }
    }

    /** Moves the cursor over and over, speeding up the longer the space bar is held. */
    private fun startCursorRepeat(dir: Int) {
        stopRepeating()
        val startedAt = SystemClock.uptimeMillis()
        val r = object : Runnable {
            override fun run() {
                moveCursor(dir)
                val held = SystemClock.uptimeMillis() - startedAt
                // 180 ms between steps at first, easing down to 20 ms after about three seconds.
                val interval = (180L - held / 18L).coerceIn(20L, 180L)
                uiHandler.postDelayed(this, interval)
            }
        }
        repeatRunnable = r
        uiHandler.post(r)
    }

    private fun moveCursor(dir: Int) {
        val ic = currentInputConnection ?: return
        val keyCode = if (dir > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        currentWord.clear()
        previousWord = ""
        pendingCorrection = null
        spaceAfterWord = false
        lastWasSpace = false
        sentenceEndPending = false
        lastCharWordish = false
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

    private fun letterOf(config: KeyConfig): Char? {
        if (config.action != KeyAction.CHAR || config.label.length != 1) return null
        val c = config.label[0]
        return if (c.isLetter()) c.lowercaseChar() else null
    }

    private fun buildKeyRects(root: View): List<KeyRect> {
        val rects = ArrayList<KeyRect>(allKeys.size)
        val letters = ArrayList<LetterRect>()
        for (key in allKeys) {
            val rect = key.rawHitRectIn(root)
            rects.add(KeyRect(key, rect))
            val ch = letterOf(key.config)
            if (ch != null) letters.add(LetterRect(key, rect, ch))
        }
        keyRects = rects
        letterRects = letters
        return rects
    }

    private fun resolveKeyAt(x: Int, y: Int, prior: Map<Char, Double>, priorMax: Double): KeyView? {
        val root = builtRoot ?: return null
        // Key positions only change when the layout does, so measure them once per layout.
        val rects = keyRects ?: buildKeyRects(root)
        var hit: KeyView? = null
        for (entry in rects) {
            if (entry.rect.contains(x, y)) {
                hit = entry.key
                break
            }
        }
        // Space, shift, backspace and the rest are exactly where they are drawn.
        if (hit != null && letterOf(hit.config) == null) return hit
        return chooseLetterKey(x, y, hit, prior, priorMax)
    }

    /**
     * Picks a letter key from where the finger landed AND which letter the word
     * model expects next. The finger counts for most: the centre of any key
     * always types that key. Only near the line between two keys does the
     * expected letter tip the choice, and the strength setting says how much.
     */
    private fun chooseLetterKey(
        x: Int, y: Int, hit: KeyView?, letterPrior: Map<Char, Double>, letterPriorMax: Double
    ): KeyView? {
        val strength = predictionStrength
        if (hit != null && (strength <= 0 || letterPriorMax <= 0.0)) return hit
        val weight = 0.7 * strength / 100.0
        var bestKey: KeyView? = null
        var bestScore = -Double.MAX_VALUE
        for (lr in letterRects) {
            val w = lr.rect.width()
            val h = lr.rect.height()
            if (w <= 0 || h <= 0) continue
            val dx = (x - lr.rect.exactCenterX()) / w
            val dy = (y - lr.rect.exactCenterY()) / h
            val d2 = (dx * dx + dy * dy).toDouble()
            if (d2 > MAX_TOUCH_D2) continue
            var score = -d2 / (2.0 * TOUCH_SIGMA * TOUCH_SIGMA)
            if (weight > 0.0 && letterPriorMax > 0.0) {
                val p = letterPrior[lr.letter] ?: 0.0
                score += weight * ln(maxOf(p, PRIOR_FLOOR) / letterPriorMax)
            }
            if (score > bestScore) {
                bestScore = score
                bestKey = lr.key
            }
        }
        return bestKey ?: hit
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
            spaceAfterWord = false
            lastWasSpace = false
            sentenceEndPending = false
            lastCharWordish = false
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
                // selStart/selEnd are kept current by onUpdateSelection, so there's no
                // blocking question to the app on every backspace.
                if (selStart != selEnd && selStart >= 0 && selEnd >= 0) {
                    ic.commitText("", 1)
                    selEnd = selStart
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
        if (config.action != KeyAction.SPACE) {
            spaceAfterWord = false
            lastWasSpace = false
        }

        when (config.action) {
            KeyAction.ENTER -> {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                finishWord(currentWord.toString(), 1f)
                previousWord = WordPredictor.START
                sentenceEndPending = false
                lastCharWordish = false
                autoShift()
            }
            KeyAction.SHIFT -> {
                // Turning off a shift the keyboard turned on means "lowercase here".
                if (shiftIsAuto && shiftState == ShiftState.ONCE) autoCapCancelled = true
                shiftIsAuto = false
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
                val now = SystemClock.uptimeMillis()
                if (autoPeriodAllowed && lastWasSpace && currentWord.isEmpty() &&
                    now - lastSpaceTime <= doubleSpaceMs && textBeforeIsWordThenSpace(ic)
                ) {
                    // A second quick tap on the space bar turns "word " into "word. ".
                    ic.deleteSurroundingText(1, 0)
                    ic.commitText(". ", 1)
                    spaceAfterWord = false
                    lastWasSpace = false
                    lastCharWordish = false
                    previousWord = WordPredictor.START
                    autoShift()
                    updatePrediction()
                    return
                }
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
                    correctionFor(typed)?.let { matchCase(typed, it) }
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
                val hadWord = typed.isNotEmpty() || lastCharWordish
                finishWord(correction ?: typed, if (rejected) 3f else 1f)
                spaceAfterWord = hadWord
                lastWasSpace = true
                lastSpaceTime = now
                lastCharWordish = false
                if (sentenceEndPending) {
                    sentenceEndPending = false
                    autoShift()
                }
            }
            KeyAction.CHAR -> {
                var text = config.commitOverride ?: outputFor(config)
                if (text.length == 1 && text[0].isLowerCase() && currentWord.isEmpty() &&
                    config.commitOverride == null && autoCapAllowed && shiftState == ShiftState.OFF &&
                    !autoCapCancelled && atSentenceStart(ic)
                ) {
                    text = text.uppercase()
                }
                val fixed = if (isPunctuation(text)) correctBeforePunctuation(ic) else null
                if (fixed != null) {
                    currentWord.setLength(0)
                    currentWord.append(fixed)
                }
                ic.commitText(text, 1)
                val isLetter = text.length == 1 && text[0].isLetter()
                if (isLetter) autoCapCancelled = false
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
                lastCharWordish = text.length == 1 && text[0].isLetterOrDigit()
                sentenceEndPending = text.length == 1 && (text[0] == '.' || text[0] == '!' || text[0] == '?')
                if (shiftState == ShiftState.ONCE) {
                    shiftState = ShiftState.OFF
                    shiftIsAuto = false
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
        val ic = currentInputConnection ?: return
        pendingCorrection = null
        val fixed = if (isPunctuation(text)) correctBeforePunctuation(ic) else null
        ic.commitText(text, 1)
        finishWord(fixed ?: currentWord.toString(), 1f)
        val endsSentence = text.length == 1 && (text[0] == '.' || text[0] == '!' || text[0] == '?')
        if (endsSentence) previousWord = WordPredictor.START
        spaceAfterWord = false
        lastWasSpace = false
        lastCharWordish = false
        // "!" and "?" are flicks on the comma key, so they end sentences here too.
        sentenceEndPending = endsSentence
        autocorrectSuppressedWord = null
        updatePrediction()
    }

    private fun isApostrophe(c: Char): Boolean = c == '\'' || c == '\u2019'

    private fun isPunctuation(text: String): Boolean =
        text.length == 1 && text[0] in ".,!?;:"

    /**
     * Autocorrect the word being typed when punctuation ends it (not just the
     * space bar), so "rhe." becomes "the.". Returns the corrected word, or null
     * if it was left alone. Backspace right after undoes it, like with space.
     */
    private fun correctBeforePunctuation(ic: InputConnection): String? {
        val typed = currentWord.toString()
        val suppressed = autocorrectSuppressedWord
        autocorrectSuppressedWord = null
        if (typed.isEmpty() || typed == suppressed || isApostrophe(typed.last()) || !autocorrectAllowed) return null
        val correction = correctionFor(typed)?.let { matchCase(typed, it) } ?: return null
        ic.deleteSurroundingText(typed.length, 0)
        ic.commitText(correction, 1)
        pendingCorrection = typed to correction
        pendingCorrectionPrev = previousWord
        return correction
    }

    /**
     * Whether the cursor sits where a sentence starts: at the very beginning,
     * after a new line, or after ". " / "! " / "? ". Checked right before the
     * first letter of a word is typed, so capitals never depend on earlier
     * state having been kept perfectly in step with the app.
     */
    private fun atSentenceStart(ic: InputConnection): Boolean {
        val before = ic.getTextBeforeCursor(6, 0) ?: return false
        var i = before.length - 1
        if (i < 0) return true
        if (before[i] == '\n') return true
        if (before[i] != ' ') return false
        while (i >= 0 && before[i] == ' ') i--
        if (i < 0) return before.length < 6
        val c = before[i]
        return c == '.' || c == '!' || c == '?' || c == '\n'
    }

    /**
     * The word just ended: learn it (after the previous word), add it to the
     * context (the last two words, which the predictor uses), and start a
     * fresh current word.
     */
    private fun finishWord(word: String, weight: Float) {
        val bare = word.trimEnd('\'', '\u2019')
        if (bare.isNotEmpty()) {
            if (learningAllowed) {
                WordPredictor.learn(previousWord, bare, weight)
                scheduleFlush()
            }
            previousWord = WordPredictor.pushContext(previousWord, bare)
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
        val plainText = isText && !isPassword && !isStructured
        autoCapAllowed = plainText && KeyboardPrefs.getAutoCapitalize(this)
        autoPeriodAllowed = plainText && KeyboardPrefs.getDoubleSpacePeriod(this)
    }

    private fun textBeforeIsWordThenSpace(ic: InputConnection): Boolean {
        val before = ic.getTextBeforeCursor(2, 0)
        if (before == null || before.length < 2) return spaceAfterWord
        val prior = before[0]
        return before[1] == ' ' && (prior.isLetterOrDigit() || prior == ')' || prior == ']' ||
                prior == '"' || prior == '\'' || prior == '\u2019' || prior == '%')
    }

    /** The word with what has been typed shown normally and the rest of it dimmed. */
    private fun ghostText(word: String, typed: String): CharSequence {
        if (word.length > typed.length && word.startsWith(typed, ignoreCase = true)) {
            val styled = SpannableString(word)
            styled.setSpan(
                ForegroundColorSpan(Color.argb(110, 255, 255, 255)),
                typed.length, word.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            return styled
        }
        return word
    }

    /**
     * Hotkeys when idle; three word suggestions while a word is being typed.
     * The best guess goes in the middle, with the part you haven't typed yet
     * dimmed; a flick up on the space bar accepts it. If the word being typed
     * isn't a real word, it also appears on the left in quotes: tapping that
     * keeps it as typed and adds it to your own words.
     */
    private fun updateSuggestions() {
        if (gifMode) return
        val icons = iconStrip ?: return
        val strip = suggestionStrip ?: return
        val typed = currentWord.toString()

        if (typed.isEmpty() || !autocorrectAllowed || suggestionViews.size < 3) {
            acceptWord = null
            strip.visibility = View.GONE
            icons.visibility = View.VISIBLE
            return
        }

        val words = ArrayList<String>()
        correctionFor(typed)?.let { words.add(matchCase(typed, it)) }
        for (w in WordPredictor.suggestions(previousWord, typed, 4)) {
            val shown = matchCase(typed, w)
            if (words.none { it.equals(shown, ignoreCase = true) }) words.add(shown)
        }
        val best = words.getOrNull(0)
        val literal = if (typed.length >= 2 && !isApostrophe(typed.last()) &&
            (!WordPredictor.isKnown(typed) || correctionFor(typed) != null)
        ) "\"" + typed + "\"" else null

        val left: CharSequence?
        val centre: CharSequence?
        val right: CharSequence?
        if (literal != null && best != null) {
            left = literal
            centre = ghostText(best, typed)
            right = words.getOrNull(1)
        } else if (literal != null) {
            left = null
            centre = literal
            right = null
        } else {
            left = words.getOrNull(1)
            centre = if (best != null) ghostText(best, typed) else typed
            right = words.getOrNull(2)
        }
        acceptWord = best

        val slots = arrayOf(left, centre, right)
        for (i in 0 until 3) {
            val tv = suggestionViews[i]
            tv.text = slots[i] ?: ""
            tv.setTypeface(null, if (i == 1) Typeface.BOLD else Typeface.NORMAL)
        }
        icons.visibility = View.GONE
        strip.visibility = View.VISIBLE
    }

    /** Replace the word being typed with a tapped suggestion, then add a space. */
    private fun onSuggestionTapped(text: String) {
        val ic = currentInputConnection ?: return
        val typed = currentWord.toString()
        if (typed.isEmpty() || text.isEmpty()) return

        // The quoted word on the left means "keep what I typed, and add it to my words".
        val isLiteral = text.length >= 3 && text.first() == '"' && text.last() == '"'
        val word = if (isLiteral) text.substring(1, text.length - 1) else text

        if (word != typed) {
            ic.deleteSurroundingText(typed.length, 0)
            ic.commitText("$word ", 1)
        } else {
            ic.commitText(" ", 1)
        }
        if (isLiteral && WordPredictor.addWord(word)) {
            Toast.makeText(this, "Added \"" + word + "\" to your words.", Toast.LENGTH_SHORT).show()
        }
        // Backspace right after undoes the swap, just like an autocorrection.
        pendingCorrection = if (word != typed) typed to word else null
        pendingCorrectionPrev = previousWord
        autocorrectSuppressedWord = null
        corrTyped = null
        acceptWord = null
        finishWord(word, 1f)
        // A space was committed with the word, so a quick second space can still make a period.
        spaceAfterWord = true
        lastWasSpace = true
        lastSpaceTime = SystemClock.uptimeMillis()
        lastCharWordish = false
        updatePrediction()
    }

    /** Keeps the touch model's idea of the next letter current. Cheap, so it runs on every keystroke. */
    /** Whether the word model should steer touches at all right now. */
    private fun priorActive(): Boolean =
        predictionStrength > 0 && !gifMode && autocorrectAllowed && page == KeyboardPage.LETTERS

    private fun refreshLetterPrior() {
        if (!priorActive()) {
            letterPrior = emptyMap()
            letterPriorMax = 0.0
            return
        }
        val dist = WordPredictor.letterDistribution(previousWord, currentWord.toString())
        letterPrior = dist
        var max = 0.0
        for (v in dist.values) if (v > max) max = v
        letterPriorMax = max
    }

    /** The touch model updates right away; the suggestion bar can wait for a pause in typing. */
    private fun updatePrediction() {
        refreshLetterPrior()
        uiHandler.removeCallbacks(predictionRunnable)
        uiHandler.postDelayed(predictionRunnable, predictionDelayMs)
    }

    // ---------- Auto-capitalize ----------

    /** Shift on for the next letter, unless shift is already on or locked. */
    private fun autoShift() {
        if (!autoCapAllowed || shiftState != ShiftState.OFF) return
        shiftState = ShiftState.ONCE
        shiftIsAuto = true
        refreshLabels()
    }

    /** Ask the app once whether the cursor sits at the start of a sentence. */
    private fun refreshAutoCap() {
        if (!autoCapAllowed || gifMode) return
        val ic = currentInputConnection ?: return
        val atSentenceStart = ic.getCursorCapsMode(InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0
        if (atSentenceStart) {
            autoShift()
        } else if (shiftIsAuto && shiftState == ShiftState.ONCE) {
            shiftState = ShiftState.OFF
            shiftIsAuto = false
            refreshLabels()
        }
    }

    private fun updatePredictionNow() {
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
        spaceAfterWord = false
        lastWasSpace = false
        lastCharWordish = false
        sentenceEndPending = false
        corrTyped = null
        autoCapCancelled = false
        predictionStrength = KeyboardPrefs.getPredictionStrength(this)
        selStart = info?.initialSelStart ?: 0
        selEnd = info?.initialSelEnd ?: 0
        applyFieldPolicy(info)
        loadHaptics()
        if (!restarting) {
            shiftState = ShiftState.OFF
            shiftIsAuto = false
            page = KeyboardPage.LETTERS
            if (::keyboardContainer.isInitialized) populateKeyboard()
        }
        refreshAutoCap()
        refreshLetterPrior()
        updateSuggestions()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selStart = newSelStart
        selEnd = newSelEnd
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        cancelAllTouches()
        uiHandler.removeCallbacks(predictionRunnable)
        uiHandler.removeCallbacks(flushRunnable)
        stopListening()
        WordPredictor.flush()
    }

    private fun loadHaptics() {
        hapticLevel = KeyboardPrefs.getHapticIntensity(this)
        hapticEffect = if (hapticLevel > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val amplitude = ((hapticLevel / 100f) * 255).toInt().coerceIn(1, 255)
            VibrationEffect.createOneShot(12L, amplitude)
        } else {
            null
        }
    }

    private fun vibrateKey() {
        if (hapticLevel < 0) loadHaptics()
        if (hapticLevel <= 0) return
        val v = vibrator ?: return
        val effect = hapticEffect
        if (effect != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(effect as VibrationEffect)
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(12L)
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()
}
