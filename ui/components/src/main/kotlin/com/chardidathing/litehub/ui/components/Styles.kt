package com.chardidathing.litehub.ui.components

import android.content.res.ColorStateList
import android.util.TypedValue
import android.widget.EditText
import android.widget.TextView
import com.chardidathing.litehub.ui.tokens.ResolvedTheme

// platform text views dressed in the theme's tokens, for forms and panels rather than tiles
fun TextView.styled(style: ResolvedTheme.Text, color: Int): TextView = apply {
    typeface = style.typeface
    setTextSize(TypedValue.COMPLEX_UNIT_PX, style.size)
    setTextColor(color)
}

fun EditText.styledInput(theme: ResolvedTheme): EditText = apply {
    styled(theme.type.body1, theme.colors.onBackground)
    setHintTextColor(theme.colors.onBackground)
    backgroundTintList = ColorStateList.valueOf(theme.colors.primary)
    isSingleLine = true
}
