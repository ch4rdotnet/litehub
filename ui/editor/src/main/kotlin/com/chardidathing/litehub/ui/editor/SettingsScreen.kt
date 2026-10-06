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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// the hub's own settings. sections down the left, the picked one on the right, one set of edits
// across all of them until save or cancel. a list section (calendars) shows its items, each
// edited on its own page. action sections come first and hold buttons and read only rows
// instead of fields (the pin, the hub's status, the log)
@SuppressLint("ViewConstructor")
class SettingsScreen(
    context: Context,
    private val theme: ResolvedTheme,
    private val sections: List<SettingsSection>,
    current: JsonObject,
    private val actions: List<ActionSection>,
    private val host: Host,
) : LinearLayout(context) {

    // items is label to action, asked for again whenever the section is shown. info hands back
    // rows to show under them, whenever it has them
    class ActionSection(
        val name: String,
        val items: () -> List<Pair<String, () -> Unit>> = { emptyList() },
        val info: ((List<InfoRow>) -> Unit) -> Unit = { },
    )

    // a heading when there's no value. bad shows in the error colour (a feed that's failing)
    class InfoRow(val label: String, val value: String? = null, val bad: Boolean = false)

    interface Host {
        fun pickEntity(domains: List<String>, onPicked: (String) -> Unit)
        // a blank item for a list section
        fun newItem(section: String): JsonObject
        // values a section action fills in, or why it couldn't
        fun action(id: String, done: (Result<JsonObject>) -> Unit)
        fun save(values: JsonObject)
        fun cancel()
    }

    private val values = HashMap<String, JsonElement>(current)
    private val tabs = ArrayList<ButtonView>()
    private val pane = LinearLayout(context).apply { orientation = VERTICAL }
    private val problem = TextView(context).styled(theme.type.body2, theme.colors.error)
    private val bar = LinearLayout(context).apply { orientation = HORIZONTAL }
    private var picked = 0
    // bumped on every pane change, info that turns up for a pane that's gone is dropped
    private var shown = 0

    init {
        orientation = HORIZONTAL
        setBackgroundColor(theme.colors.background)
        val side = theme.spacing.xl.toInt()
        val gap = theme.spacing.s.toInt()
        setPadding(side, side, side, side)

        val names = actions.map { it.name } + sections.map { it.name }
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
        bar.addView(ButtonView(context, theme, "save") { host.save(JsonObject(values)) })
        bar.addView(space(gap))
        bar.addView(ButtonView(context, theme, "close") { host.cancel() })
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
        shown++
        bar.visibility = VISIBLE
        tabs.forEachIndexed { i, t -> t.checked = i == index }
        pane.removeAllViews()
        if (index < actions.size) {
            showActions(actions[index])
            return
        }
        val section = sections[index - actions.size]
        heading(section.name)
        if (section.fields.isNotEmpty()) pane.addView(FieldForm(context, theme, section.fields, values, null, host::pickEntity))
        if (section.items != null) showItems(section)
        for (action in section.actions) {
            pane.addView(space(theme.spacing.m.toInt()))
            pane.addView(ButtonView(context, theme, action.label) { run(action.value) })
        }
    }

    private fun showActions(group: ActionSection) {
        heading(group.name)
        for ((label, action) in group.items()) {
            pane.addView(space(theme.spacing.m.toInt()))
            pane.addView(ButtonView(context, theme, label, action))
        }
        val rows = LinearLayout(context).apply { orientation = VERTICAL }
        pane.addView(rows)
        val token = shown
        group.info { list -> if (token == shown) fillInfo(rows, list) }
    }

    private fun fillInfo(box: LinearLayout, list: List<InfoRow>) {
        box.removeAllViews()
        for (r in list) {
            val value = r.value
            if (value == null) {
                box.addView(space(theme.spacing.l.toInt()))
                box.addView(TextView(context).styled(theme.type.subtitle1, theme.colors.onBackground).apply { text = r.label })
                continue
            }
            val line = LinearLayout(context).apply { orientation = HORIZONTAL }
            line.setPadding(0, theme.spacing.xs.toInt(), 0, theme.spacing.xs.toInt())
            line.addView(TextView(context).styled(theme.type.body2, theme.colors.onBackground).apply { text = r.label }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            line.addView(
                TextView(context).styled(theme.type.body2, if (r.bad) theme.colors.error else theme.colors.onBackground).apply { text = value },
                LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, VALUE_SHARE),
            )
            box.addView(line)
        }
    }

    private fun items(section: SettingsSection) = (values[section.id] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun name(item: JsonObject) = (item["name"] as? JsonPrimitive)?.contentOrNull?.ifBlank { null }

    // tap one to change it, and a button for a new one
    private fun showItems(section: SettingsSection) {
        val list = items(section)
        if (list.isEmpty()) pane.addView(TextView(context).styled(theme.type.body2, theme.colors.onBackground).apply { text = "no ${section.name} yet" })
        list.forEachIndexed { i, item ->
            pane.addView(space(theme.spacing.s.toInt()))
            pane.addView(ButtonView(context, theme, name(item) ?: "unnamed ${section.itemName}") { editItem(section, i) })
        }
        pane.addView(space(theme.spacing.m.toInt()))
        pane.addView(ButtonView(context, theme, "add a ${section.itemName}") { editItem(section, -1) })
    }

    // one item on its own page. done puts it back in the list, save still has to be pressed
    private fun editItem(section: SettingsSection, index: Int) {
        shown++
        val list = items(section)
        val item = HashMap<String, JsonElement>(list.getOrNull(index) ?: host.newItem(section.id))
        bar.visibility = GONE
        pane.removeAllViews()
        heading(name(JsonObject(item)) ?: "new ${section.itemName}")
        pane.addView(FieldForm(context, theme, section.items.orEmpty(), item, null, host::pickEntity))
        pane.addView(space(theme.spacing.m.toInt()))
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        row.addView(ButtonView(context, theme, "done") {
            val next = list.toMutableList()
            if (index < 0) next += JsonObject(item) else next[index] = JsonObject(item)
            values[section.id] = JsonArray(next)
            show(picked)
        })
        if (index >= 0) {
            row.addView(space(theme.spacing.s.toInt()))
            row.addView(ButtonView(context, theme, "remove") {
                values[section.id] = JsonArray(list.filterIndexed { i, _ -> i != index })
                show(picked)
            })
        }
        row.addView(space(theme.spacing.s.toInt()))
        row.addView(ButtonView(context, theme, "back") { show(picked) })
        pane.addView(row)
    }

    private fun run(action: String) {
        say("", error = false)
        host.action(action) { result ->
            result.onSuccess {
                values.putAll(it)
                show(picked)
            }.onFailure { say(it.message ?: "that didn't work", error = true) }
        }
    }

    private fun heading(text: String) {
        pane.addView(TextView(context).styled(theme.type.h6, theme.colors.onBackground).apply { this.text = text })
    }

    private fun space(size: Int) = View(context).apply { layoutParams = LayoutParams(size, size) }

    private companion object {
        // fields get three times the room the section list does
        const val RIGHT_SHARE = 3f
        // in a read only row the value gets three times the label's room
        const val VALUE_SHARE = 3f
    }
}
