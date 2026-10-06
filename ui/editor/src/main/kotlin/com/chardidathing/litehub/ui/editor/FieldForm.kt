package com.chardidathing.litehub.ui.editor

import android.annotation.SuppressLint
import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.chardidathing.litehub.core.model.FieldKind
import com.chardidathing.litehub.core.model.Hex
import com.chardidathing.litehub.core.model.SchemaField
import com.chardidathing.litehub.core.model.shownWith
import com.chardidathing.litehub.ui.components.ButtonView
import com.chardidathing.litehub.ui.components.SwatchView
import com.chardidathing.litehub.ui.tokens.ResolvedTheme
import com.chardidathing.litehub.ui.widgets.Legend
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

// controls for a list of schema fields, editing one map of values in place. a field with a
// showIf comes and goes as the values it depends on change. a blank text box drops its key
@SuppressLint("ViewConstructor")
class FieldForm(
    context: Context,
    private val theme: ResolvedTheme,
    private val fields: List<SchemaField>,
    private val values: MutableMap<String, JsonElement>,
    private val legend: Legend?,
    private val pickEntity: (domains: List<String>, onPicked: (String) -> Unit) -> Unit,
) : LinearLayout(context) {

    private val rows = HashMap<String, View>()

    init {
        orientation = VERTICAL
        val gap = theme.spacing.m.toInt()
        for (field in fields) {
            val row = LinearLayout(context).apply { orientation = VERTICAL }
            row.addView(space(gap))
            row.addView(TextView(context).styled(theme.type.subtitle2, theme.colors.onBackground).apply { text = field.label })
            // text boxes take the row (the colour row has one), buttons stay their own size
            val c = control(field)
            val fill = c is EditText || field.kind == FieldKind.COLOR
            row.addView(c, LayoutParams(if (fill) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            rows[field.key] = row
            addView(row)
        }
        refresh()
    }

    private fun refresh() {
        for (field in fields) rows[field.key]?.visibility = if (field.shownWith(values)) VISIBLE else GONE
    }

    private fun text(key: String) = (values[key] as? JsonPrimitive)?.contentOrNull

    private fun control(field: SchemaField): View = when (field.kind) {
        FieldKind.TEXT, FieldKind.NUMBER, FieldKind.TIME, FieldKind.SECRET -> input(field)
        FieldKind.TOGGLE -> toggle(field)
        FieldKind.CHOICE -> choice(field)
        FieldKind.ENTITY -> entity(field)
        FieldKind.ENTITIES -> entities(field)
        FieldKind.COLOR -> color(field)
        FieldKind.CALENDARS -> sources(field, legend?.calendars.orEmpty())
        FieldKind.FEEDS -> sources(field, legend?.feeds.orEmpty())
    }

    private fun input(field: SchemaField) = EditText(context).styledInput(theme).apply {
        setText(text(field.key) ?: (field.default as? JsonPrimitive)?.contentOrNull.orEmpty())
        inputType = when (field.kind) {
            FieldKind.NUMBER -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            FieldKind.TIME -> InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
            FieldKind.SECRET -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else -> InputType.TYPE_CLASS_TEXT
        }
        hint = when (field.kind) {
            FieldKind.TIME -> "22:00"
            FieldKind.SECRET -> "leave blank to keep it"
            else -> ""
        }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val t = s?.toString()?.trim().orEmpty()
                val number = t.toDoubleOrNull()
                when {
                    t.isEmpty() -> values.remove(field.key)
                    field.kind != FieldKind.NUMBER -> values[field.key] = JsonPrimitive(t)
                    number == null -> values[field.key] = JsonPrimitive(t)
                    number % 1.0 == 0.0 -> values[field.key] = JsonPrimitive(number.toLong())
                    else -> values[field.key] = JsonPrimitive(number)
                }
                refresh()
            }
        })
    }

    private fun toggle(field: SchemaField): ButtonView {
        lateinit var b: ButtonView
        fun on() = ((values[field.key] ?: field.default) as? JsonPrimitive)?.booleanOrNull == true
        b = ButtonView(context, theme, if (on()) "on" else "off") {
            values[field.key] = JsonPrimitive(!on())
            b.label = if (on()) "on" else "off"
            b.checked = on()
            b.requestLayout()
            refresh()
        }
        b.checked = on()
        return b
    }

    private fun choice(field: SchemaField): LinearLayout {
        val box = LinearLayout(context).apply { orientation = HORIZONTAL }
        val buttons = ArrayList<ButtonView>()
        fun mark() = field.options.forEachIndexed { i, o -> buttons[i].checked = text(field.key) == o.value }
        for (option in field.options) {
            val b = ButtonView(context, theme, option.label) {
                values[field.key] = JsonPrimitive(option.value)
                mark()
                refresh()
            }
            buttons += b
            box.addView(b)
            box.addView(space(theme.spacing.s.toInt()))
        }
        mark()
        return box
    }

    // auto, the theme's palette, or any "#rrggbb" typed in the box beside them
    private fun color(field: SchemaField): LinearLayout {
        val box = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val gap = theme.spacing.s.toInt()
        val swatches = ArrayList<Pair<Int, SwatchView>>()
        lateinit var auto: ButtonView
        lateinit var typed: EditText
        fun mark() {
            val current = text(field.key)?.let(Hex::parse)
            auto.checked = current == null
            for ((c, v) in swatches) v.checked = c == current
        }
        fun pick(value: String) {
            values[field.key] = JsonPrimitive(value)
            typed.setText(value)
            mark()
        }
        auto = ButtonView(context, theme, "auto") { pick("") }
        box.addView(auto)
        for (c in theme.palette.distinct()) {
            val swatch = SwatchView(context, theme, c) { pick(Hex.of(c)) }
            swatches += c to swatch
            box.addView(space(gap))
            box.addView(swatch)
        }
        typed = EditText(context).styledInput(theme).apply {
            hint = "#rrggbb"
            setText(text(field.key).orEmpty())
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    val t = s?.toString()?.trim().orEmpty()
                    if (t != text(field.key)) {
                        values[field.key] = JsonPrimitive(t)
                        mark()
                    }
                }
            })
        }
        box.addView(space(gap))
        box.addView(typed, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        mark()
        return box
    }

    private fun entity(field: SchemaField): ButtonView {
        lateinit var button: ButtonView
        fun label() = text(field.key) ?: "pick an entity"
        button = ButtonView(context, theme, label()) {
            pickEntity(field.domains) { id ->
                values[field.key] = JsonPrimitive(id)
                button.label = label()
                button.requestLayout()
            }
        }
        return button
    }

    // the ones picked so far, tap one to take it off, and a button to add another
    private fun entities(field: SchemaField): LinearLayout {
        val box = LinearLayout(context).apply { orientation = VERTICAL }
        fun ids() = (values[field.key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        fun set(next: List<String>) {
            values[field.key] = JsonArray(next.map(::JsonPrimitive))
        }
        fun fill() {
            box.removeAllViews()
            for (id in ids()) {
                // posted, the button being tapped is one of the views that goes
                box.addView(ButtonView(context, theme, id) { box.post { set(ids() - id); fill() } })
                box.addView(space(theme.spacing.xs.toInt()))
            }
            box.addView(ButtonView(context, theme, if (ids().isEmpty()) "add an entity" else "add another") {
                pickEntity(field.domains) { id ->
                    if (id !in ids()) set(ids() + id)
                    fill()
                }
            })
        }
        fill()
        return box
    }

    // one toggle per source, none on means every source
    private fun sources(field: SchemaField, ids: List<String>): LinearLayout {
        val box = LinearLayout(context).apply { orientation = HORIZONTAL }
        if (ids.isEmpty()) {
            box.addView(TextView(context).styled(theme.type.body2, theme.colors.onBackground).apply { text = "none set up in sources.json" })
            return box
        }
        val chosen = (values[field.key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toMutableSet() ?: mutableSetOf()
        for (id in ids) {
            lateinit var b: ButtonView
            b = ButtonView(context, theme, legend?.names?.get(id) ?: id) {
                if (!chosen.remove(id)) chosen += id
                b.checked = id in chosen
                if (chosen.isEmpty()) values.remove(field.key) else values[field.key] = JsonArray(ids.filter { it in chosen }.map(::JsonPrimitive))
            }
            b.checked = id in chosen
            box.addView(b)
            box.addView(space(theme.spacing.s.toInt()))
        }
        return box
    }

    private fun space(size: Int) = View(context).apply { layoutParams = LayoutParams(size, size) }
}
