package com.example.customkeyboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class SetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }

        val instructionsText = TextView(this).apply {
            text = "Step 1: Enable this keyboard in system settings.\n\n" +
                    "Step 2: Switch to it from the keyboard picker.\n\n" +
                    "You only need to do this once."
            textSize = 16f
        }

        val enableButton = Button(this).apply {
            text = "1. Open keyboard settings"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
        }

        val switchButton = Button(this).apply {
            text = "2. Switch keyboard"
            setOnClickListener {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            }
        }

        val settingsButton = Button(this).apply {
