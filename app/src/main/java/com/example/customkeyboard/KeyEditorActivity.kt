package com.example.customkeyboard

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * The top half is the real keyboard, drawn by the same code the keyboard itself
 * uses. Tap a key to edit it. The controls underneath change the keyboard's
 * size and look, and the preview updates as you drag.
 */
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

        screen.addView(
            ScrollView(this).apply { addView(controls) },
            LinearLayout.LayoutParams(matchParent, 0, 1f)
        )
        setContentView(screen)
        rebuildPreview()
    }

    private fun rebuildPreview() {
        previewHolder.removeAllViews()
        val built = KeyboardBuilder.build(
            context = this,
            page = KeyboardPage.LETTERS,
            onTap = { _ -> },
            onFlick = { _, _ -> }
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
                    colorHex = selectedColor
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
