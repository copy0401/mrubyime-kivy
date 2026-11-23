package com.example.mrubyime

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout

/**
 * Minimal IME host for testing: one auto-focused EditText, so the system
 * reliably shows our InputMethodService (and therefore the Kivy frontend)
 * as soon as the activity appears.
 */
class ImeTestActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val field = EditText(this).apply {
            hint = "test field"
            textSize = 20f
            setPadding(24, 24, 24, 24)
        }
        val parent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 200, 32, 32)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(field)
        }
        setContentView(parent)

        // Ask for the IME to show up front.
        field.requestFocus()
        field.postDelayed({
            val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(field, 0)
        }, 250)
    }
}