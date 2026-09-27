package com.example.customkeyboard

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class KeyEditorActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rebuildPreview()
    }

    private fun rebuildPreview() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        root.addView(TextView(this).apply {
            text = "Tap any key to change its label, flick corners, or color."
            textSize = 14f
            setPadding(0, 0, 0, 24)
        })

        KeyboardLayout.ROWS.forEachIndexed { rowIndex, row ->
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 8; bottomMargin = 8 }
            }
            row.forEachIndexed { colIndex, defaultConfig ->
                val effective = KeyLayoutStore.effectiveConfig(this, rowIndex, colIndex, defaultConfig)
                val button = Button(this).apply {
                    text = effective.label
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, effective.weight
                    ).apply { marginStart = 4; marginEnd = 4 }
                    if (effective.colorHex != null) setBackgroundColor(Color.parseColor(effective.colorHex))
                    setOnClickListener { openEditDialog(rowIndex, colIndex) }
                }
                rowLayout.addView(button)
            }
            root.addView(rowLayout)
        }

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun openEditDialog(row: Int, col: Int) {
        val default = KeyboardLayout.ROWS[row][col]
        val current = KeyLayoutStore.effectiveConfig(this, row, col, default)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 16, 40, 8)
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
            setPadding(0, 24, 0, 8)
        })
        val swatchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val palette = listOf(null, "#E74C3C", "#E67E22", "#F1C40F", "#2ECC71", "#3498DB", "#9B59B6")
        palette.forEach { hex ->
            val swatch = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(72, 72).apply { marginEnd = 12 }
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
}
