package com.example.customkeyboard

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.ScrollView

class SettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        fun section(title: String) {
            root.addView(TextView(this).apply {
                text = title
                textSize = 18f
                setPadding(0, 32, 0, 12)
            })
        }

        fun label(text: String): TextView {
            val tv = TextView(this).apply {
                this.text = text
                textSize = 14f
                setPadding(0, 8, 0, 4)
            }
            root.addView(tv)
            return tv
        }

        // ── Key height ──────────────────────────────────────────────
        section("Key height")
        val heightLabel = label("Height: ${KeyboardPrefs.getKeyHeight(this)} dp")
        root.addView(SeekBar(this).apply {
            max = 80
            progress = KeyboardPrefs.getKeyHeight(this@SettingsActivity)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = progress.coerceAtLeast(36)
                    heightLabel.text = "Height: $value dp"
                    KeyboardPrefs.setKeyHeight(this@SettingsActivity, value)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        })

        // ── Key spacing ─────────────────────────────────────────────
        section("Key spacing")
        val spacingLabel = label("Spacing: ${KeyboardPrefs.getKeySpacing(this)} dp")
        root.addView(SeekBar(this).apply {
            max = 12
            progress = KeyboardPrefs.getKeySpacing(this@SettingsActivity)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    spacingLabel.text = "Spacing: $progress dp"
                    KeyboardPrefs.setKeySpacing(this@SettingsActivity, progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        })

        // ── Haptic intensity ────────────────────────────────────────
        section("Haptic feedback")
        val hapticLabel = label("Intensity: ${KeyboardPrefs.getHapticIntensity(this)}")
        root.addView(SeekBar(this).apply {
            max = 100
            progress = KeyboardPrefs.getHapticIntensity(this@SettingsActivity)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    hapticLabel.text = "Intensity: $progress"
                    KeyboardPrefs.setHapticIntensity(this@SettingsActivity, progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        })

        // ── Toggles ─────────────────────────────────────────────────
        section("Options")

        root.addView(CheckBox(this).apply {
            text = "Show corner flick hints"
            isChecked = KeyboardPrefs.getShowHints(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                KeyboardPrefs.setShowHints(this@SettingsActivity, checked)
            }
        })

        root.addView(CheckBox(this).apply {
            text = "Highlight predicted keys"
            isChecked = KeyboardPrefs.getShowPredictedHighlight(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                KeyboardPrefs.setShowPredictedHighlight(this@SettingsActivity, checked)
            }
        })

        root.addView(CheckBox(this).apply {
            text = "Show number row"
            isChecked = KeyboardPrefs.getShowNumberRow(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                KeyboardPrefs.setShowNumberRow(this@SettingsActivity, checked)
            }
        })

        root.addView(TextView(this).apply {
            text = "\nChanges take effect the next time the keyboard is shown."
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }
}

