package com.example.customkeyboard

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
            toggleRow("Capitalize new sentences", KeyboardPrefs.getAutoCapitalize(this)) {
                KeyboardPrefs.setAutoCapitalize(this, it)
            }
        )
        controls.addView(
            toggleRow("Double-tap space for a period", KeyboardPrefs.getDoubleSpacePeriod(this)) {
                KeyboardPrefs.setDoubleSpacePeriod(this, it)
            }
        )
        controls.addView(Button(this).apply {
            text = "Clear learned words"
            setOnClickListener {
                WordPredictor.clearLearned(this@KeyEditorActivity)
                Toast.makeText(this@KeyEditorActivity, "Learned words cleared", Toast.LENGTH_SHORT).show()
            }
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
}
