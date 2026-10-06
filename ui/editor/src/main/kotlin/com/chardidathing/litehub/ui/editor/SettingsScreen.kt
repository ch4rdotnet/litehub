package com.chardidathing.litehub.ui.editor

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.chardidathing.litehub.core.model.SettingsSection
import com.chardidathing.litehub.ui.components.ButtonView
import com.chardidathing.litehub.ui.tokens.ResolvedTheme
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// the hub's own settings. sections down the left, the picked one's fields on the right, one
// set of edits across all of them until save or cancel. action sections hold buttons instead
// of fields (the pin, android's settings)
@SuppressLint("ViewConstructor")
class SettingsScreen(
    context: Context,
    private val theme: ResolvedTheme,
    private val sections: List<SettingsSection>,
    current: JsonObject,
    private val actions: List<ActionSection>,
    private val host: Host,
) : LinearLayout(context) {

    // items is label to action, asked for again whenever the section is shown
    class ActionSection(val name: String, val items: () -> List<Pair<String, () -> Unit>>)

    interface Host {
        fun pickEntity(domains: List<String>, onPicked: (String) -> Unit)
        fun save(values: JsonObject)
        fun cancel()
    }

    private val values = HashMap<String, JsonElement>(current)
    private val tabs = ArrayList<ButtonView>()
    private val pane = LinearLayout(context).apply { orientation = VERTICAL }
    private val problem = TextView(context).styled(theme.type.body2, theme.colors.error)
    private var picked = 0

    init {
        orientation = HORIZONTAL
        setBackgroundColor(theme.colors.background)
        val side = theme.spacing.xl.toInt()
        val gap = theme.spacing.s.toInt()
        setPadding(side, side, side, side)

        val names = sections.map { it.name } + actions.map { it.name }
        val left = LinearLayout(context).apply { orientation = VERTICAL }
        left.addView(TextView(context).styled(theme.type.h5, theme.colors.onBackground).apply { text = "settings" })
        left.addView(space(theme.spacing.m.toInt()))
        names.forEachIndexed { i, name ->
            val tab = ButtonView(context, theme, name) { show(i) }
            tabs += tab
            left.addView(tab, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            left.addView(space(gap))
        }
        addView(ScrollView(context).apply { addView(left) }, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        addView(space(side))

        val right = LinearLayout(context).apply { orientation = VERTICAL }
        right.addView(ScrollView(context).apply { addView(pane) }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        right.addView(problem)
        val bar = LinearLayout(context).apply { orientation = HORIZONTAL }
        bar.addView(ButtonView(context, theme, "save") { host.save(JsonObject(values)) })
        bar.addView(space(gap))
        bar.addView(ButtonView(context, theme, "cancel") { host.cancel() })
        right.addView(bar)
        addView(right, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, RIGHT_SHARE))
        show(0)
    }

    // red for a refusal, plain for anything else
    fun say(text: String, error: Boolean) {
        problem.setTextColor(if (error) theme.colors.error else theme.colors.onBackground)
        problem.text = text
    }

    // after an action changed something its label shows (a pin set, the screen admin granted)
    fun refresh() = show(picked)

    private fun show(index: Int) {
        picked = index
        tabs.forEachIndexed { i, t -> t.checked = i == index }
        pane.removeAllViews()
        val section = sections.getOrNull(index)
        if (section != null) {
            pane.addView(TextView(context).styled(theme.type.h6, theme.colors.onBackground).apply { text = section.name })
            pane.addView(FieldForm(context, theme, section.fields, values, null, host::pickEntity))
            return
        }
        val group = actions[index - sections.size]
        pane.addView(TextView(context).styled(theme.type.h6, theme.colors.onBackground).apply { text = group.name })
        for ((label, action) in group.items()) {
            pane.addView(space(theme.spacing.m.toInt()))
            pane.addView(ButtonView(context, theme, label, action))
        }
    }

    private fun space(size: Int) = View(context).apply { layoutParams = LayoutParams(size, size) }

    private companion object {
        // fields get three times the room the section list does
        const val RIGHT_SHARE = 3f
    }
}
