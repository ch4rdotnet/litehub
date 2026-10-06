package com.chardidathing.litehub.ui.editor

import android.annotation.SuppressLint
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.chardidathing.litehub.core.model.EntityChoice
import com.chardidathing.litehub.ui.components.ButtonView
import com.chardidathing.litehub.ui.tokens.ResolvedTheme

// every entity ha has, grouped by area and filtered as you type. domains narrows it to what
// the field can use
@SuppressLint("ViewConstructor")
class EntityPicker(
    context: Context,
    private val theme: ResolvedTheme,
    private val domains: List<String>,
    private val onPicked: (String) -> Unit,
    onCancel: () -> Unit,
) : LinearLayout(context) {

    private val search = EditText(context).styledInput(theme).apply { hint = "search" }
    private val list = LinearLayout(context).apply { orientation = VERTICAL }
    private val status = TextView(context).styled(theme.type.body1, theme.colors.onBackground)
    private var choices: List<EntityChoice> = emptyList()

    init {
        orientation = VERTICAL
        setBackgroundColor(theme.colors.background)
        val side = theme.spacing.xl.toInt()
        setPadding(side, side, side, side)
        val top = LinearLayout(context).apply { orientation = HORIZONTAL }
        top.addView(search, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(ButtonView(context, theme, "cancel", onCancel))
        addView(top)
        addView(status)
        addView(ScrollView(context).apply { addView(list) }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        status.text = "loading entities from home assistant"
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = fill()
        })
    }

    fun show(result: Result<List<EntityChoice>>) {
        result.onSuccess {
            choices = it.filter { c -> domains.isEmpty() || c.id.substringBefore('.') in domains }
            status.text = ""
            fill()
        }.onFailure {
            status.setTextColor(theme.colors.error)
            status.text = "couldn't load entities, ${it.message}"
        }
    }

    private fun fill() {
        list.removeAllViews()
        val q = search.text.toString().trim().lowercase()
        val shown = choices.filter { q.isEmpty() || q in it.id.lowercase() || q in it.name.lowercase() }.take(MAX_ROWS)
        if (shown.isEmpty() && choices.isNotEmpty()) status.text = "nothing matches \"$q\"" else if (choices.isNotEmpty()) status.text = ""
        var area: String? = null
        var first = true
        val gap = theme.spacing.s.toInt()
        for (c in shown) {
            if (first || c.area != area) {
                first = false
                area = c.area
                list.addView(TextView(context).styled(theme.type.subtitle2, theme.colors.onBackground).apply {
                    text = c.area ?: "no area"
                    setPadding(0, theme.spacing.m.toInt(), 0, gap)
                })
            }
            list.addView(ButtonView(context, theme, "${c.name}  ·  ${c.id}") { onPicked(c.id) }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = gap })
        }
    }

    private companion object {
        // more than this is a search that hasn't narrowed yet, laying them all out is slow
        const val MAX_ROWS = 150
    }
}
