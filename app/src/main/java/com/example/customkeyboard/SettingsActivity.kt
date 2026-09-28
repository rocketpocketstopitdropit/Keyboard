package com.example.customkeyboard

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "Keyboard Settings"
            textSize = 20f
            setPadding(0, 0, 0, 32)
        }
        content.addView(title)

        content.addView(
            sliderRow(
                label = "Key height",
                min = 36, max = 72,
                initial = KeyboardPrefs.getKeyHeight(this),
                unit = "dp"
            ) { KeyboardPrefs.setKeyHeight(this, it) }
        )

        content.addView(
            sliderRow(
                label = "Key spacing",
                min = 0, max = 8,
                initial = KeyboardPrefs.getKeySpacing(this),
                unit = "dp"
            ) { KeyboardPrefs.setKeySpacing(this, it) }
        )

        content.addView(
            sliderRow(
                label = "Haptic intensity",
                min = 0, max = 100,
                initial = KeyboardPrefs.getHapticIntensity(this),
                unit = "%"
            ) { KeyboardPrefs.setHapticIntensity(this, it) }
        )

        content.addView(
            toggleRow(
                label = "Show number row",
                initial = KeyboardPrefs.getShowNumberRow(this)
            ) { KeyboardPrefs.setShowNumberRow(this, it) }
        )

        content.addView(
            toggleRow(
                label = "Show corner hints",
                initial = KeyboardPrefs.getShowHints(this)
            ) { KeyboardPrefs.setShowHints(this, it) }
        )

        content.addView(
            toggleRow(
                label = "Highlight predicted key",
                initial = KeyboardPrefs.getShowPredictedHighlight(this)
            ) { KeyboardPrefs.setShowPredictedHighlight(this, it) }
        )

        val note = TextView(this).apply {
            text = "\nChanges apply next time the keyboard opens."
            textSize = 13f
            alpha = 0.7f
        }
        content.addView(note)

        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun sliderRow(
        label: String, min: Int, max: Int, initial: Int, unit: String,
        onChange: (Int) -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 24, 0, 24)
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
            setPadding(0, 20, 0, 20)
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
}
