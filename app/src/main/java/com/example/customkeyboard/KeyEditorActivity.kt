package com.example.customkeyboard

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged

class KeyEditorActivity : Activity() {

    private lateinit var previewHolder: FrameLayout
    /** Lets the lookup box refresh the added-words list below it. */
    private var refreshWordsListLater: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WordPredictor.init(this)

        val matchParent = ViewGroup.LayoutParams.MATCH_PARENT
        val wrapContent = ViewGroup.LayoutParams.WRAP_CONTENT

        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(24), dp(12), dp(12))
        }

        screen.addView(TextView(this).apply {
            text = "Tap any key to edit it. The controls below change this preview right away."
            textSize = 13f
            setPadding(0, 0, 0, dp(12))
        })

        previewHolder = FrameLayout(this)
        screen.addView(previewHolder, LinearLayout.LayoutParams(matchParent, wrapContent))

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        controls.addView(
            toggleRow("Split Thumb layout", KeyboardPrefs.getSplitLayout(this)) {
                KeyboardPrefs.setSplitLayout(this, it); rebuildPreview()
            }
        )
        controls.addView(TextView(this).apply {
            text = "Split halves on a wide screen, a compact version on a narrow one. Turn it off for the " +
                    "original layout. Your customised keys work in both."
            textSize = 12f
            alpha = 0.7f
        })
        controls.addView(
            sliderRow("Key height", 24, 72, KeyboardPrefs.getKeyHeight(this), "dp") {
                KeyboardPrefs.setKeyHeight(this, it); rebuildPreview()
            }
        )
        controls.addView(
            sliderRow("Key width", 50, 100, KeyboardPrefs.getKeyWidth(this), "%") {
                KeyboardPrefs.setKeyWidth(this, it); rebuildPreview()
            }
        )
        controls.addView(
            sliderRow("Key spacing", 0, 8, KeyboardPrefs.getKeySpacing(this), "dp") {
                KeyboardPrefs.setKeySpacing(this, it); rebuildPreview()
            }
        )
        controls.addView(
            sliderRow("Haptic intensity", 0, 100, KeyboardPrefs.getHapticIntensity(this), "%") {
                KeyboardPrefs.setHapticIntensity(this, it)
            }
        )
        controls.addView(
            toggleRow("Show number row", KeyboardPrefs.getShowNumberRow(this)) {
                KeyboardPrefs.setShowNumberRow(this, it); rebuildPreview()
            }
        )
        controls.addView(
            toggleRow("Show corner hints", KeyboardPrefs.getShowHints(this)) {
                KeyboardPrefs.setShowHints(this, it); rebuildPreview()
            }
        )
        controls.addView(
            toggleRow("Highlight predicted key", KeyboardPrefs.getShowPredictedHighlight(this)) {
                KeyboardPrefs.setShowPredictedHighlight(this, it); rebuildPreview()
            }
        )

        controls.addView(
            toggleRow("Learn from my typing", KeyboardPrefs.getLearnWords(this)) {
                KeyboardPrefs.setLearnWords(this, it)
            }
        )
        controls.addView(
            sliderRow("Prediction strength", 0, 100, KeyboardPrefs.getPredictionStrength(this), "%") {
                KeyboardPrefs.setPredictionStrength(this, it)
            }
        )
        controls.addView(TextView(this).apply {
            text = "How much the keyboard widens the key it expects next. Higher helps when the guess is right; " +
                    "0 turns it off. Where your finger lands always counts most."
            textSize = 12f
            alpha = 0.7f
        })
        controls.addView(
            toggleRow("Capitalize new sentences", KeyboardPrefs.getAutoCapitalize(this)) {
                KeyboardPrefs.setAutoCapitalize(this, it)
            }
        )
        controls.addView(
            toggleRow("Double-tap space for a period", KeyboardPrefs.getDoubleSpacePeriod(this)) {
                KeyboardPrefs.setDoubleSpacePeriod(this, it)
            }
        )
        // ---- Look up a word: is it in the keyboard's word data, and what happens when it's typed? ----
        controls.addView(TextView(this).apply {
            text = "Look up a word"
            textSize = 14f
            setPadding(0, dp(16), 0, dp(4))
        })
        val lookupInput = EditText(this).apply {
            hint = "Type a word to check"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val lookupResult = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(4), 0, dp(4))
        }
        val lookupButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val takeOutButton = Button(this).apply { text = "Remove from keyboard" }
        val putBackButton = Button(this).apply { text = "Add to keyboard" }
        lookupButtons.addView(takeOutButton, LinearLayout.LayoutParams(0, wrapContent, 1f))
        lookupButtons.addView(putBackButton, LinearLayout.LayoutParams(0, wrapContent, 1f))
        val blockedList = TextView(this).apply {
            textSize = 12f
            alpha = 0.7f
            setPadding(0, dp(4), 0, dp(4))
        }

        fun refreshBlockedList() {
            val list = WordPredictor.blockedWords()
            blockedList.text = if (list.isEmpty()) "" else "Removed by you (${list.size}): " + list.joinToString(", ")
            blockedList.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        }

        fun refreshLookup() {
            val typed = lookupInput.text.toString().trim()
            if (typed.isEmpty()) {
                lookupResult.text = "Shows whether a word is in the keyboard's words, how common it is, " +
                    "and what the keyboard does with it."
                lookupButtons.visibility = View.GONE
                return
            }
            val st = WordPredictor.lookup(typed)
            val lines = ArrayList<String>()
            val usable = (st.inDictionary && !st.blocked) || st.added
            when {
                st.blocked -> lines.add("✗ \"${st.word}\" is in the word list, but you removed it, so it's never suggested.")
                st.inDictionary -> {
                    val how = when {
                        st.zipf == null -> ""
                        st.zipf >= 5.0 -> ", very common"
                        st.zipf >= 4.0 -> ", common"
                        st.zipf >= 3.0 -> ", uncommon"
                        else -> ", rare"
                    }
                    val place = st.rank?.let { " (#${"%,d".format(it)} by frequency$how)" } ?: ""
                    lines.add("✓ \"${st.word}\" is in the word list$place.")
                }
                st.added -> lines.add("✓ \"${st.word}\" is one of your added words.")
                st.removedByApp -> lines.add("✗ \"${st.word}\" isn't in the word list (the app filters it out as a common typo).")
                !st.plain -> lines.add("✗ \"${st.word}\" isn't a plain word (letters and inner apostrophes only).")
                else -> lines.add("✗ \"${st.word}\" isn't in the word list.")
            }
            if (st.inDictionary && st.added && !st.blocked) lines.add("It's also in your added words.")
            if (st.timesTyped >= 0.5f) lines.add("You've typed it about ${Math.round(st.timesTyped)} times recently.")
            if (st.correction != null) {
                lines.add("Typed on its own, space changes it to \"${st.correction}\".")
            } else if (usable) {
                lines.add("It's kept exactly as typed.")
            }
            if (!usable && st.near.isNotEmpty()) {
                lines.add("Closest words: " + st.near.joinToString(", "))
            }
            lookupResult.text = lines.joinToString("\n")

            takeOutButton.visibility = if (usable) View.VISIBLE else View.GONE
            putBackButton.text = if (st.blocked) "Put back" else "Add to keyboard"
            putBackButton.visibility = if (!usable && (st.blocked || st.plain)) View.VISIBLE else View.GONE
            lookupButtons.visibility = View.VISIBLE
        }

        takeOutButton.setOnClickListener {
            val w = lookupInput.text.toString().trim()
            if (WordPredictor.blockWord(w)) {
                Toast.makeText(this, "Removed \"$w\". It won't be suggested again.", Toast.LENGTH_SHORT).show()
            }
            refreshLookup()
            refreshBlockedList()
            refreshWordsListLater?.invoke()
        }
        putBackButton.setOnClickListener {
            val w = lookupInput.text.toString().trim()
            val ok = WordPredictor.addWord(w)
            Toast.makeText(
                this,
                if (ok) "\"$w\" is back in the keyboard." else "Couldn't add that word.",
                Toast.LENGTH_SHORT
            ).show()
            refreshLookup()
            refreshBlockedList()
            refreshWordsListLater?.invoke()
        }
        lookupInput.doAfterTextChanged { refreshLookup() }
        controls.addView(lookupInput)
        controls.addView(lookupResult)
        controls.addView(lookupButtons)
        controls.addView(blockedList)
        refreshLookup()
        refreshBlockedList()

        val wordsList = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(4), 0, dp(8))
        }
        fun refreshWordsList() {
            val list = WordPredictor.addedWords()
            wordsList.text = if (list.isEmpty()) "No added words yet." else list.joinToString(", ")
        }
        val wordInput = EditText(this).apply {
            hint = "Type a word to add or remove"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        controls.addView(TextView(this).apply {
            text = "My words (only real words are ever suggested; add your own here)"
            textSize = 14f
            setPadding(0, dp(16), 0, dp(4))
        })
        controls.addView(wordInput)
        val wordButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        wordButtons.addView(Button(this).apply {
            text = "Add"
            setOnClickListener {
                val w = wordInput.text.toString().trim()
                val message = when {
                    w.isEmpty() -> "Type a word first."
                    WordPredictor.addWord(w) -> "Added to your words."
                    else -> "Already a known word, or not a plain word."
                }
                Toast.makeText(this@KeyEditorActivity, message, Toast.LENGTH_SHORT).show()
                wordInput.setText("")
                refreshWordsList()
            }
        }, LinearLayout.LayoutParams(0, wrapContent, 1f))
        wordButtons.addView(Button(this).apply {
            text = "Remove"
            setOnClickListener {
                val w = wordInput.text.toString().trim()
                val message = when {
                    w.isEmpty() -> "Type a word first."
                    WordPredictor.removeWord(w) -> "Removed."
                    else -> "That isn't one of your added words."
                }
                Toast.makeText(this@KeyEditorActivity, message, Toast.LENGTH_SHORT).show()
                wordInput.setText("")
                refreshWordsList()
            }
        }, LinearLayout.LayoutParams(0, wrapContent, 1f))
        wordButtons.addView(Button(this).apply {
            text = "Clear all"
            setOnClickListener {
                WordPredictor.clearAddedWords()
                refreshWordsList()
            }
        }, LinearLayout.LayoutParams(0, wrapContent, 1f))
        controls.addView(wordButtons)
        controls.addView(wordsList)
        refreshWordsList()
        refreshWordsListLater = { refreshWordsList() }
        controls.addView(Button(this).apply {
            text = "Clear learned words"
            setOnClickListener {
                WordPredictor.clearLearned(this@KeyEditorActivity)
                Toast.makeText(this@KeyEditorActivity, "Learned words cleared", Toast.LENGTH_SHORT).show()
            }
        })
        controls.addView(Button(this).apply {
            text = "Export my words to a file"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, "keyboard-words.txt")
                }
                startActivityForResult(intent, REQUEST_EXPORT_WORDS)
            }
        })
        controls.addView(Button(this).apply {
            text = "Import words from a file"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/*"
                }
                startActivityForResult(intent, REQUEST_IMPORT_WORDS)
            }
        })
        controls.addView(TextView(this).apply {
            text = "The file lists your added words and everything learned from your typing. " +
                "Edit it in any text app; importing replaces what's on the phone with what's in the file."
            textSize = 12f
            alpha = 0.7f
            setPadding(0, 0, 0, dp(4))
        })
        controls.addView(TextView(this).apply {
            text = "Backup"
            textSize = 14f
            setPadding(0, dp(16), 0, dp(4))
        })
        controls.addView(Button(this).apply {
            text = "Copy settings backup"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("Keyboard settings", SettingsBackup.export(this@KeyEditorActivity))
                )
                Toast.makeText(
                    this@KeyEditorActivity,
                    "Copied. Paste it into a note to keep it safe.",
                    Toast.LENGTH_LONG
                ).show()
            }
        })
        controls.addView(Button(this).apply {
            text = "Restore settings from clipboard"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = clipboard.primaryClip
                val text = if (clip != null && clip.itemCount > 0) {
                    clip.getItemAt(0).coerceToText(this@KeyEditorActivity)?.toString()
                } else {
                    null
                }
                if (text.isNullOrBlank()) {
                    Toast.makeText(this@KeyEditorActivity, "Nothing on the clipboard.", Toast.LENGTH_SHORT).show()
                } else {
                    try {
                        val count = SettingsBackup.restore(this@KeyEditorActivity, text)
                        WordPredictor.reloadUserWords()
                        Toast.makeText(this@KeyEditorActivity, "Restored $count settings.", Toast.LENGTH_SHORT).show()
                        recreate()
                    } catch (e: Exception) {
                        Toast.makeText(
                            this@KeyEditorActivity,
                            "That isn't a keyboard settings backup.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        })
        controls.addView(TextView(this).apply {
            text = "GIF search (Klipy API key)"
            textSize = 14f
            setPadding(0, dp(16), 0, dp(4))
        })
        controls.addView(EditText(this).apply {
            hint = "Paste your key from klipy.com/developers"
            setText(KeyboardPrefs.getKlipyKey(this@KeyEditorActivity))
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            doAfterTextChanged {
                KeyboardPrefs.setKlipyKey(this@KeyEditorActivity, it?.toString()?.trim() ?: "")
            }
        })

        screen.addView(
            ScrollView(this).apply { addView(controls) },
            LinearLayout.LayoutParams(matchParent, 0, 1f)
        )

        // Every change above already saves itself instantly — this button is
        // just a reassuring confirmation, not a requirement.
        screen.addView(Button(this).apply {
            text = "Save"
            setOnClickListener {
                Toast.makeText(this@KeyEditorActivity, "Settings saved", Toast.LENGTH_SHORT).show()
            }
        })

        setContentView(screen)
        rebuildPreview()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQUEST_EXPORT_WORDS -> try {
                val text = WordListFile.export()
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    ?: throw Exception("couldn't open the file")
                Toast.makeText(this, "Words exported.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
            REQUEST_IMPORT_WORDS -> {
                val result = try {
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: throw Exception("couldn't open the file")
                    if (bytes.size > 5_000_000) throw Exception("that file is too big")
                    WordListFile.import(String(bytes, Charsets.UTF_8))
                } catch (e: Exception) {
                    AlertDialog.Builder(this)
                        .setTitle("Import failed")
                        .setMessage(e.message ?: "That file couldn't be read.")
                        .setPositiveButton("OK", null)
                        .show()
                    return
                }
                val lines = ArrayList<String>()
                result.added?.let { lines.add("Added words: $it") }
                result.words?.let { lines.add("Learned words: $it") }
                result.pairs?.let { lines.add("Word pairs: $it") }
                if (result.skipped.isNotEmpty()) {
                    lines.add("")
                    lines.add("Skipped ${result.skipped.size} line(s):")
                    lines.addAll(result.skipped.take(15))
                    if (result.skipped.size > 15) lines.add("…and ${result.skipped.size - 15} more")
                }
                AlertDialog.Builder(this)
                    .setTitle("Words imported")
                    .setMessage(lines.joinToString("\n"))
                    .setPositiveButton("OK") { _, _ -> recreate() }
                    .setOnCancelListener { recreate() }
                    .show()
            }
        }
    }

    private fun rebuildPreview() {
        previewHolder.removeAllViews()
        val built = KeyboardBuilder.build(
            context = this,
            page = KeyboardPage.LETTERS,
            onTap = { _ -> },
            onFlick = { _, _ -> },
            onAccessory = { _ -> }
        )
        for (kv in built.keys) {
            val address = kv.tag as? KeyAddress ?: continue
            kv.setOnClickListener { openEditDialog(address.row, address.col) }
        }
        previewHolder.addView(
            built.root,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun openEditDialog(row: Int, col: Int) {
        val default = KeyboardLayout.defaultFor(row, col)
        val current = KeyLayoutStore.effectiveConfig(this, row, col, default)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }

        fun field(hint: String, initial: String?): EditText {
            val et = EditText(this)
            et.hint = hint
            et.setText(initial ?: "")
            container.addView(et)
            return et
        }

        val labelField = field("Label (what typing this key produces)", current.label)
        val tlField = field("Flick top-left (optional)", current.topLeft)
        val trField = field("Flick top-right (optional)", current.topRight)
        val blField = field("Flick bottom-left (optional)", current.bottomLeft)
        val brField = field("Flick bottom-right (optional)", current.bottomRight)

        var selectedColor: String? = current.colorHex
        container.addView(TextView(this).apply {
            text = "Key color (tap one; gray = use theme default):"
            setPadding(0, dp(12), 0, dp(8))
        })
        val swatchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val palette = listOf(null, "#E74C3C", "#E67E22", "#F1C40F", "#2ECC71", "#3498DB", "#9B59B6")
        palette.forEach { hex ->
            val swatch = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(6) }
                setBackgroundColor(if (hex != null) Color.parseColor(hex) else Color.DKGRAY)
                setOnClickListener { selectedColor = hex }
            }
            swatchRow.addView(swatch)
        }
        container.addView(swatchRow)

        var customHeightEnabled = current.heightDp != null
        var customHeightValue = current.heightDp ?: KeyboardPrefs.getKeyHeight(this)
        container.addView(
            toggleRow("Custom height for this key", customHeightEnabled) { customHeightEnabled = it }
        )
        container.addView(
            sliderRow("Key height override", 24, 90, customHeightValue, "dp") { customHeightValue = it }
        )

        AlertDialog.Builder(this)
            .setTitle("Edit key")
            .setView(ScrollView(this).apply { addView(container) })
            .setPositiveButton("Save") { _, _ ->
                val newLabel = labelField.text.toString().ifBlank { current.label }
                val override = KeyOverride(
                    label = newLabel,
                    topLeft = tlField.text.toString().ifBlank { null },
                    topRight = trField.text.toString().ifBlank { null },
                    bottomLeft = blField.text.toString().ifBlank { null },
                    bottomRight = brField.text.toString().ifBlank { null },
                    colorHex = selectedColor,
                    heightDp = if (customHeightEnabled) customHeightValue else null
                )
                KeyLayoutStore.setOverride(this, row, col, override)
                rebuildPreview()
            }
            .setNeutralButton("Reset to default") { _, _ ->
                KeyLayoutStore.clearOverride(this, row, col)
                rebuildPreview()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sliderRow(
        label: String, min: Int, max: Int, initial: Int, unit: String,
        onChange: (Int) -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val valueText = TextView(this)
        val labelRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        labelRow.addView(TextView(this).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        valueText.text = "$initial$unit"
        labelRow.addView(valueText)
        row.addView(labelRow)

        val seekBar = SeekBar(this).apply {
            this.max = max - min
            progress = (initial - min).coerceIn(0, max - min)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = progress + min
                    valueText.text = "$value$unit"
                    if (fromUser) onChange(value)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        row.addView(seekBar)
        return row
    }

    private fun toggleRow(label: String, initial: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        row.addView(TextView(this).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(Switch(this).apply {
            isChecked = initial
            setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { _, checked ->
                onChange(checked)
            })
        })
        return row
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()

    companion object {
        private const val REQUEST_EXPORT_WORDS = 41
        private const val REQUEST_IMPORT_WORDS = 42
    }
}
