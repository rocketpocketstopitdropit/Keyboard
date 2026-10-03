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
import android.widget.ScrollView
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
    /** Split Thumb: suggestions have their own spot, so the hotkeys always stay visible. */
    private var suggestionsSeparate = false
    private var stripEndInset = 0

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

    // The space after the cursor was put there by the keyboard (an accepted
    // suggestion or an autocorrection), so punctuation typed next goes right
    // after the word instead of after that space.
    private var autoSpaced = false
    // That auto space now follows punctuation ("word, "), so a space tap is already done.
    private var punctAttached = false

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

    /** False on Split Thumb, whose cursor key does the cursor moving instead. */
    private var spaceBarMovesCursor = true

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
            onSuggestion = { word -> onSuggestionTapped(word) },
            onStripFlickUp = { if (!wordMode) enterWordMode() }
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
        suggestionsSeparate = built.separateSuggestions
        stripEndInset = built.stripEndInset
        if (gifMode) attachGifPanel(built) else if (wordMode) attachWordPanel(built)
        // Split Thumb has its own cursor key, so there the space bar only types spaces.
        spaceBarMovesCursor = !KeyboardPrefs.getSplitLayout(this)
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
        stopWordMode()
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
            setPadding(dp(4), dp(4), dp(2) + stripEndInset, 0)
            // Split Thumb's strip has no background of its own under the box.
            setBackgroundColor(getColor(R.color.keyboard_background))
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

    // ---------- Word check (flick up on the suggestions) ----------

    private var wordMode = false
    private val wordQuery = StringBuilder()
    private var wordQueryView: TextView? = null
    private var wordHeaderView: TextView? = null
    private var wordContent: LinearLayout? = null
    private var wordScroll: ScrollView? = null
    private var wordShowDefinitions = false
    private var wordResults: List<WordInfo>? = null
    /** Some words couldn't be looked up online and were checked against the keyboard's own dictionary. */
    private var wordOffline = false
    private var wordStatus: String? = null
    private var wordToken = 0
    private val wordCheckRunnable = Runnable { runWordCheck() }

    private val goodColor = Color.parseColor("#6FD08C")
    private val badColor = Color.parseColor("#FF8A80")

    private fun enterWordMode() {
        if (gifMode) stopGifMode()
        page = KeyboardPage.LETTERS
        currentWord.clear()
        pendingCorrection = null
        clearAutoSpace()
        acceptWord = null
        wordQuery.setLength(0)
        wordResults = null
        wordStatus = null
        wordOffline = false
        wordShowDefinitions = false
        wordMode = true
        populateKeyboard()
    }

    private fun exitWordMode() {
        if (!wordMode) return
        stopWordMode()
        if (::keyboardContainer.isInitialized) populateKeyboard()
        updatePrediction()
    }

    /** Turn word check off without rebuilding the keyboard (the caller does that). */
    private fun stopWordMode() {
        wordMode = false
        wordQuery.setLength(0)
        wordToken++
        uiHandler.removeCallbacks(wordCheckRunnable)
    }

    /** A box in place of the hotkeys, and a panel above the keys for the answer. */
    private fun attachWordPanel(built: BuiltKeyboard) {
        built.iconStrip.visibility = View.GONE
        built.suggestionStrip.visibility = View.GONE

        val queryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(2) + stripEndInset, 0)
            setBackgroundColor(getColor(R.color.keyboard_background))
        }
        queryRow.addView(TextView(this).apply {
            text = "✕"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(36))
            setOnClickListener { exitWordMode() }
        })
        val queryView = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            maxLines = 1
            // Long pasted text keeps its end (and the caret) in view.
            ellipsize = android.text.TextUtils.TruncateAt.START
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f)
        }
        queryRow.addView(queryView)
        queryRow.addView(TextView(this).apply {
            text = "Paste"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(10), 0, dp(10), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.argb(50, 255, 255, 255))
                cornerRadius = dp(14).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30))
            setOnClickListener { pasteIntoWordBox() }
        })
        built.stripHost.addView(
            queryRow,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val panel = SwipeFrame(this) { dir ->
            // Right to left shows the definition; left to right goes back to spelling.
            val defs = dir < 0
            if (defs != wordShowDefinitions) {
                wordShowDefinitions = defs
                renderWordPanel()
            }
        }
        panel.setBackgroundColor(getColor(R.color.keyboard_background))
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(4), dp(8), dp(4))
        }
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val header = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.LTGRAY)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(header)
        headerRow.addView(TextView(this).apply {
            text = "Type it ⏎"
            textSize = 13f
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(4), dp(6), dp(4))
            setOnClickListener { useWordFromBox() }
        })
        column.addView(headerRow)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(content)
        }
        column.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        panel.addView(
            column,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        // Index 1 = between the top strip and the key rows.
        built.root.addView(panel, 1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(140)))

        wordQueryView = queryView
        wordHeaderView = header
        wordContent = content
        wordScroll = scroll
        updateWordQueryView()
        renderWordPanel()
    }

    private fun updateWordQueryView() {
        wordQueryView?.text = if (wordQuery.isEmpty()) {
            "🔍  Type or paste a word"
        } else {
            "🔍  ${wordQuery}▏"
        }
    }

    private fun scheduleWordCheck() {
        updateWordQueryView()
        uiHandler.removeCallbacks(wordCheckRunnable)
        uiHandler.postDelayed(wordCheckRunnable, 600)
    }

    private fun pasteIntoWordBox() {
        val clip = clipboardManager.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this)?.toString() else null
        val clean = text?.replace(Regex("\\s+"), " ")?.trim()?.take(200)
        if (clean.isNullOrEmpty()) {
            Toast.makeText(this, "Nothing copied to paste.", Toast.LENGTH_SHORT).show()
            return
        }
        wordQuery.setLength(0)
        wordQuery.append(clean)
        updateWordQueryView()
        runWordCheck()
    }

    /** Puts what's in the box into the app being typed in, and closes the checker. */
    private fun useWordFromBox() {
        val text = wordQuery.toString().trim()
        exitWordMode()
        if (text.isEmpty()) return
        currentInputConnection?.commitText("$text ", 1)
        clearAutoSpace()
        spaceAfterWord = true
        lastWasSpace = false
        lastCharWordish = false
        updatePrediction()
    }

    /** In word check the keys type into the box, not into the app. */
    private fun handleWordKey(config: KeyConfig): Boolean {
        return when (config.action) {
            KeyAction.CHAR -> {
                val text = config.commitOverride ?: outputFor(config)
                if (text.isNotEmpty()) {
                    wordQuery.append(text)
                    if (shiftState == ShiftState.ONCE) {
                        shiftState = ShiftState.OFF
                        shiftIsAuto = false
                        refreshLabels()
                    }
                    scheduleWordCheck()
                }
                true
            }
            KeyAction.SPACE -> {
                if (wordQuery.isNotEmpty() && wordQuery.last() != ' ') wordQuery.append(' ')
                updateWordQueryView()
                true
            }
            KeyAction.BACKSPACE -> {
                if (wordQuery.isEmpty()) {
                    exitWordMode()
                } else {
                    wordQuery.deleteCharAt(wordQuery.length - 1)
                    scheduleWordCheck()
                }
                true
            }
            KeyAction.ENTER -> {
                updateWordQueryView()
                runWordCheck()
                true
            }
            KeyAction.SYMBOLS, KeyAction.SYMBOLS_ALT, KeyAction.LETTERS -> {
                // Switching pages leaves word check; the normal handler rebuilds the keyboard.
                stopWordMode()
                false
            }
            else -> true
        }
    }

    private fun runWordCheck() {
        uiHandler.removeCallbacks(wordCheckRunnable)
        val words = WordLookup.wordsIn(wordQuery.toString())
        val token = ++wordToken
        if (words.isEmpty()) {
            wordResults = null
            wordStatus = null
            renderWordPanel()
            return
        }
        // Already looked up: show it straight away.
        val known = words.map { WordLookup.cached(it) }
        if (known.all { it != null }) {
            wordResults = known.filterNotNull()
            wordStatus = null
            wordOffline = false
            renderWordPanel()
            return
        }
        wordStatus = "Checking…"
        renderWordPanel()
        WordLookup.runAsync {
            val found = words.map { w -> try { WordLookup.lookup(w) } catch (e: Exception) { null } }
            uiHandler.post {
                if (token != wordToken || !wordMode) return@post
                // Anything that couldn't be reached online is checked on the phone instead.
                wordOffline = found.any { it == null }
                wordResults = words.mapIndexed { i, w -> found[i] ?: localWordInfo(w) }
                wordStatus = null
                renderWordPanel()
            }
        }
    }

    /** The keyboard's own dictionary, for when the internet can't be reached. */
    private fun localWordInfo(word: String): WordInfo {
        val known = WordPredictor.isKnown(word)
        val sugg = if (known) emptyList() else {
            val out = ArrayList<String>()
            Autocorrector.correctionFor(word.lowercase())?.let { out.add(it) }
            for (w in WordPredictor.suggestions("", word.lowercase().take(3), 6)) {
                if (w !in out && w != word.lowercase() && out.size < 5) out.add(w)
            }
            out
        }
        return WordInfo(word = word, found = known, suggestions = sugg)
    }

    private fun wordText(text: CharSequence, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
            setPadding(0, dp(2), 0, dp(2))
        }

    /** Replace [old] in the box with [new] (a tapped "did you mean"), then check again. */
    private fun replaceInWordBox(old: String, new: String) {
        val text = wordQuery.toString()
        val m = Regex("(?i)(?<![A-Za-z'])" + Regex.escape(old) + "(?![A-Za-z'])").find(text)
        val replaced = if (m != null) text.replaceRange(m.range, new) else new
        wordQuery.setLength(0)
        wordQuery.append(replaced)
        updateWordQueryView()
        runWordCheck()
    }

    private fun renderWordPanel() {
        val content = wordContent ?: return
        content.removeAllViews()
        wordScroll?.scrollTo(0, 0)
        wordHeaderView?.text = if (wordShowDefinitions) {
            "DEFINITION  ·  swipe → for spelling"
        } else {
            "SPELLING  ·  swipe ← for definition"
        }
        val results = wordResults
        val status = wordStatus
        when {
            status != null -> {
                content.addView(wordText(status, 15f, Color.LTGRAY))
                return
            }
            wordQuery.isBlank() || results == null -> {
                content.addView(wordText(
                    "Type a word with the keys, or tap Paste. It's checked online; swipe left for its meaning.",
                    14f, Color.LTGRAY
                ))
                return
            }
        }
        val list = results ?: return

        if (!wordShowDefinitions) {
            for (info in list) {
                val mark = if (info.found) "✓  " else "✗  "
                val line = if (info.found) "$mark${info.word}  — spelled correctly"
                else "$mark${info.word}  — not a word"
                content.addView(wordText(line, 16f, if (info.found) goodColor else badColor, bold = true))
                if (!info.found) {
                    if (info.suggestions.isEmpty()) {
                        content.addView(wordText("No close matches found.", 13f, Color.LTGRAY))
                    } else {
                        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                        row.addView(wordText("Did you mean:", 13f, Color.LTGRAY))
                        for (s in info.suggestions) {
                            row.addView(TextView(this).apply {
                                text = s
                                textSize = 15f
                                setTextColor(Color.WHITE)
                                setPadding(dp(10), dp(4), dp(10), dp(4))
                                background = android.graphics.drawable.GradientDrawable().apply {
                                    setColor(Color.argb(50, 255, 255, 255))
                                    cornerRadius = dp(12).toFloat()
                                }
                                setOnClickListener { replaceInWordBox(info.word, s) }
                            }, LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                            ).apply { marginStart = dp(6) })
                        }
                        content.addView(HorizontalScrollView(this).apply {
                            isHorizontalScrollBarEnabled = false
                            addView(row)
                        })
                    }
                }
            }
        } else {
            for (info in list) {
                val title = if (info.phonetic.isNotBlank()) "${info.word}   ${info.phonetic}" else info.word
                content.addView(wordText(title, 16f, Color.WHITE, bold = true))
                if (info.meanings.isEmpty()) {
                    val why = if (info.found) "No definition found." else "Not a word, so there's no definition."
                    content.addView(wordText(why, 13f, Color.LTGRAY))
                } else {
                    for ((pos, def) in info.meanings) {
                        val styled = SpannableString(if (pos.isNotBlank()) "$pos  $def" else def)
                        if (pos.isNotBlank()) {
                            styled.setSpan(
                                ForegroundColorSpan(Color.LTGRAY), 0, pos.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                            styled.setSpan(
                                android.text.style.StyleSpan(Typeface.ITALIC), 0, pos.length,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                        }
                        content.addView(wordText(styled, 14f, Color.WHITE))
                    }
                }
            }
        }
        val note = if (wordOffline) "Offline — checked against the keyboard's own dictionary."
        else "From Free Dictionary and Datamuse."
        content.addView(wordText(note, 10f, Color.GRAY))
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
                stopGifMode()
                stopWordMode()
                page = if (page == KeyboardPage.EMOJI) KeyboardPage.LETTERS else KeyboardPage.EMOJI
                currentWord.clear()
                populateKeyboard()
            }
            AccessoryAction.CLIPBOARD -> {
                stopGifMode()
                stopWordMode()
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
                if (!text.isNullOrBlank()) {
                    currentInputConnection?.commitText("$text ", 1)
                    clearAutoSpace()
                }
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
        /** Split Thumb's cursor key: where the finger was when the cursor last moved. */
        var cursorPad = false
        var anchorX = downX
        var anchorY = downY
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
                // That press may have closed the GIF search or word check (which rebuilds the
                // keyboard and drops this touch); don't keep deleting in the app after it.
                if (touches.get(id) !== state) return
                startRepeating(400L, 45L) { handleTap(target.config) }
                repeatOwner = id
            }
            KeyAction.SPACE -> if (spaceBarMovesCursor && !gifMode && !wordMode) scheduleSpaceHold(id)
            KeyAction.CURSOR -> state.cursorPad = true
            else -> {}
        }
    }

    /**
     * Dragging on the cursor key moves the cursor: one character for every
     * short step sideways, one line for every longer step up or down.
     */
    private fun dragCursor(state: TouchState, x: Float, y: Float) {
        val stepX = dp(14).toFloat()
        val stepY = dp(30).toFloat()
        while (x - state.anchorX >= stepX) { moveCursor(1); state.anchorX += stepX }
        while (state.anchorX - x >= stepX) { moveCursor(-1); state.anchorX -= stepX }
        while (y - state.anchorY >= stepY) { moveCursorLine(1); state.anchorY += stepY }
        while (state.anchorY - y >= stepY) { moveCursorLine(-1); state.anchorY -= stepY }
    }

    private fun pointerMove(id: Int, x: Float, y: Float) {
        val state = touches.get(id) ?: return
        state.lastX = x
        state.lastY = y
        val key = state.key ?: return
        if (state.cursorPad) {
            dragCursor(state, x, y)
            return
        }
        if (key.config.action != KeyAction.SPACE || state.spaceGesture) return
        // While searching GIFs or checking a word the space bar is just a space.
        if (gifMode || wordMode) return
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
        if (abs(dx) >= dp(24) && abs(dx) > abs(dy)) {
            if (dx > 0) {
                // A flick right puts a space just after the cursor; the cursor stays put.
                state.spaceGesture = true
                state.consumed = true
                holdRunnable?.let { uiHandler.removeCallbacks(it) }
                holdRunnable = null
                insertSpaceAhead()
            } else if (spaceBarMovesCursor) {
                // Original layout (no cursor key): a flick left moves the cursor one
                // character, and holding on keeps it going, getting faster.
                state.slideDir = -1
                state.spaceGesture = true
                moveCursor(-1)
                scheduleSpaceHold(id)
            }
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
        if (!state.backspace && !state.consumed && !state.spaceGesture && !state.cursorPad) {
            for (t in heldLetterTouches()) {
                if (t.seq >= state.seq) break
                t.consumed = true
                t.key?.resolveGesture(t.lastX - t.downX, t.lastY - t.downY)
            }
        }
        when {
            state.backspace -> key.setPressedVisual(false)
            state.consumed -> key.setPressedVisual(false)
            state.spaceGesture || state.cursorPad -> {
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

    /** Type a space on the far side of the cursor, leaving the cursor where it is. */
    private fun insertSpaceAhead() {
        val ic = currentInputConnection ?: return
        // A new cursor position of 0 means "at the start of what was just typed".
        ic.commitText(" ", 0)
        pendingCorrection = null
        clearAutoSpace()
        spaceAfterWord = false
        lastWasSpace = false
        updatePrediction()
    }

    private fun moveCursor(dir: Int) =
        sendCursorKey(if (dir > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT)

    /** Up or down one line. */
    private fun moveCursorLine(dir: Int) =
        sendCursorKey(if (dir > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP)

    private fun sendCursorKey(keyCode: Int) {
        val ic = currentInputConnection ?: return
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        currentWord.clear()
        previousWord = ""
        pendingCorrection = null
        clearAutoSpace()
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
        // The cursor key does its work while being dragged; a plain tap types nothing.
        if (config.action == KeyAction.CURSOR) return
        if (gifMode && handleGifKey(config)) return
        if (wordMode && handleWordKey(config)) return
        val ic = currentInputConnection ?: return

        if (config.action == KeyAction.CHAR && config.commitOverride == null &&
            attachPunctuation(ic, outputFor(config))
        ) return
        if (config.action == KeyAction.SPACE && swallowSpaceAfterPunctuation(ic)) return
        when (config.action) {
            // Shift and page switches (e.g. to the symbols page for ";") keep the auto space in play.
            KeyAction.SHIFT, KeyAction.SYMBOLS, KeyAction.SYMBOLS_ALT, KeyAction.LETTERS -> {}
            else -> clearAutoSpace()
        }

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
                    autoSpaced = true
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
            KeyAction.BACKSPACE, KeyAction.SPACER, KeyAction.CURSOR -> {}
        }
        updatePrediction()
    }

    private fun commitDirect(text: String) {
        if (wordMode) {
            if (text.length == 1) {
                wordQuery.append(text)
                scheduleWordCheck()
            }
            return
        }
        if (gifMode) {
            if (text.length == 1) {
                gifQuery.append(text.lowercase())
                scheduleGifSearch()
            }
            return
        }
        val ic = currentInputConnection ?: return
        if (attachPunctuation(ic, text)) return
        clearAutoSpace()
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

    private fun clearAutoSpace() {
        autoSpaced = false
        punctAttached = false
    }

    /**
     * Right after an accepted suggestion or an autocorrection the text ends in
     * "word ". Typing . ! ? , or ; then gives "word, " rather than "word ,":
     * the punctuation goes straight after the word and the space moves after it.
     */
    private fun attachPunctuation(ic: InputConnection, text: String): Boolean {
        if (!autoSpaced || currentWord.isNotEmpty() || text.length != 1 || text[0] !in ".!?,;") return false
        if (selStart != selEnd || ic.getTextBeforeCursor(1, 0)?.toString() != " ") {
            clearAutoSpace()
            return false
        }
        ic.beginBatchEdit()
        ic.deleteSurroundingText(1, 0)
        ic.commitText("$text ", 1)
        ic.endBatchEdit()
        val endsSentence = text[0] == '.' || text[0] == '!' || text[0] == '?'
        // Still an auto space, so "?!" chains the same way.
        autoSpaced = true
        punctAttached = true
        pendingCorrection = null
        autocorrectSuppressedWord = null
        spaceAfterWord = false
        lastWasSpace = false
        lastCharWordish = false
        sentenceEndPending = false
        if (endsSentence) previousWord = WordPredictor.START
        if (shiftState == ShiftState.ONCE) {
            shiftState = ShiftState.OFF
            shiftIsAuto = false
            refreshLabels()
        }
        // The space is already there, so the next sentence's capital is due now.
        if (endsSentence) autoShift()
        updatePrediction()
        return true
    }

    /** After "word, " the space is already typed, so a habitual space tap doesn't add a second one. */
    private fun swallowSpaceAfterPunctuation(ic: InputConnection): Boolean {
        if (!punctAttached || currentWord.isNotEmpty()) return false
        clearAutoSpace()
        return ic.getTextBeforeCursor(1, 0)?.toString() == " "
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
        if (gifMode || wordMode) return
        val icons = iconStrip ?: return
        val strip = suggestionStrip ?: return
        val typed = currentWord.toString()

        if (typed.isEmpty() || !autocorrectAllowed || suggestionViews.size < 3) {
            acceptWord = null
            if (suggestionsSeparate) {
                // The suggestion spot stays where it is, just empty.
                for (tv in suggestionViews) tv.text = ""
            } else {
                strip.visibility = View.GONE
                icons.visibility = View.VISIBLE
            }
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
        if (!suggestionsSeparate) icons.visibility = View.GONE
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
        autoSpaced = true
        punctAttached = false
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
        predictionStrength > 0 && !gifMode && !wordMode && autocorrectAllowed && page == KeyboardPage.LETTERS

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
        if (!autoCapAllowed || gifMode || wordMode) return
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
        exitWordMode()
        currentWord.clear()
        pendingCorrection = null
        autocorrectSuppressedWord = null
        previousWord = WordPredictor.START
        spaceAfterWord = false
        lastWasSpace = false
        lastCharWordish = false
        sentenceEndPending = false
        corrTyped = null
        clearAutoSpace()
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
