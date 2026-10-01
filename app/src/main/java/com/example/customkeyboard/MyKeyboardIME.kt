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
            override fun onResult
