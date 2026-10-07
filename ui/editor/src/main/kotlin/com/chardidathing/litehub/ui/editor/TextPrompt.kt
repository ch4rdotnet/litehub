package com.chardidathing.litehub.ui.editor

import android.annotation.SuppressLint
import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.chardidathing.litehub.ui.components.ButtonView
import com.chardidathing.litehub.ui.tokens.ResolvedTheme

// one line of typed text over the dashboard, for adding to a list and the like
@SuppressLint("ViewConstructor")
class TextPrompt(
    context: Context,
    theme: ResolvedTheme,
    title: String,
    private val onDone: (String) -> Unit,
    onCancel: () -> Unit,
    action: String = "add",
    // a passphrase, dotted out and kept off the keyboard's suggestions
    secret: Boolean = false,
) : FrameLayout(context) {

    val input: EditText = EditText(context).styledInput(theme).apply {
        imeOptions = EditorInfo.IME_ACTION_DONE
        if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        // the soft keyboard sends done, a hardware or injected enter comes through as a key event
        setOnEditorActionListener { _, action, event ->
            if (action == EditorInfo.IME_ACTION_DONE || event?.keyCode == KeyEvent.KEYCODE_ENTER) submit()
            true
        }
    }

    init {
        setBackgroundColor(theme.colors.background)
        isClickable = true
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val side = theme.spacing.xl.toInt()
            setPadding(side, side, side, side)
        }
        column.addView(TextView(context).styled(theme.type.h5, theme.colors.onBackground).apply { text = title })
        column.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(ButtonView(context, theme, action) { submit() })
        actions.addView(android.view.View(context), LinearLayout.LayoutParams(theme.spacing.m.toInt(), 1))
        actions.addView(ButtonView(context, theme, "cancel", onCancel))
        column.addView(actions)
        addView(column, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
    }

    private fun submit() {
        val text = input.text.toString().trim()
        if (text.isNotEmpty()) onDone(text)
    }
}
