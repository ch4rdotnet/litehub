package com.chardidathing.litehub.ui.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.chardidathing.litehub.ui.components.ButtonView
import com.chardidathing.litehub.ui.components.styled
import com.chardidathing.litehub.ui.components.styledInput
import com.chardidathing.litehub.ui.tokens.ResolvedTheme
import java.util.Locale

// every app in a grid under a search box, full screen. the drawer and the app picker for a tile
// are both this. icons are asked for once each and drawn as they arrive, onHold is null where
// holding an app means nothing (the picker)
@SuppressLint("ViewConstructor")
class AppDrawerView(
    context: Context,
    private val theme: ResolvedTheme,
    private val loadIcon: (app: AppEntry, size: Int, done: (Bitmap?) -> Unit) -> Unit,
    onPick: (AppEntry) -> Unit,
    onHold: ((AppEntry) -> Unit)?,
    onClose: () -> Unit,
) : LinearLayout(context) {

    private val search = EditText(context).styledInput(theme).apply { hint = "search apps" }
    private val status = TextView(context).styled(theme.type.body1, theme.colors.onBackground)
    private val grid = AppGrid(context, theme, onPick, onHold)
    private var apps: List<AppEntry> = emptyList()
    private val asked = HashSet<String>()

    init {
        orientation = VERTICAL
        setBackgroundColor(theme.colors.background)
        val side = theme.spacing.xl.toInt()
        setPadding(side, side, side, side)
        // the search box doesn't take focus on its own, a keyboard shouldn't cover the apps
        isFocusableInTouchMode = true
        descendantFocusability = FOCUS_BEFORE_DESCENDANTS
        val top = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        top.addView(search, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(ButtonView(context, theme, "close", onClose))
        addView(top)
        addView(status)
        addView(ScrollView(context).apply { addView(grid) }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        status.text = "loading apps"
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = fill()
        })
        post { requestFocus() }
    }

    fun show(result: Result<List<AppEntry>>) {
        result.onSuccess {
            apps = it
            fill()
        }.onFailure {
            status.setTextColor(theme.colors.error)
            status.text = "couldn't list apps, ${it.message}"
        }
    }

    private fun fill() {
        val q = search.text.toString().trim().lowercase(Locale.ROOT)
        val shown = apps.filter { q.isEmpty() || q in it.label.lowercase(Locale.ROOT) }
        status.text = when {
            apps.isEmpty() -> "no apps to show"
            shown.isEmpty() -> "nothing matches \"$q\""
            else -> ""
        }
        status.visibility = if (status.text.isEmpty()) GONE else VISIBLE
        grid.show(shown)
        for (app in shown) {
            if (!asked.add(app.key)) continue
            loadIcon(app, grid.iconSize) { bitmap -> if (bitmap != null) grid.icon(app.key, bitmap) }
        }
    }
}
