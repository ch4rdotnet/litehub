package com.chardidathing.litehub.ui.editor

import android.annotation.SuppressLint
import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.chardidathing.litehub.core.model.FieldKind
import com.chardidathing.litehub.core.model.SchemaField
import com.chardidathing.litehub.core.model.WidgetSchema
import com.chardidathing.litehub.ui.components.ButtonView
import com.chardidathing.litehub.ui.tokens.ResolvedTheme
import com.chardidathing.litehub.ui.widgets.Legend
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

// one widget's settings, a form built from its schema. hands back the new config json
@SuppressLint("ViewConstructor")
class SettingsSheet(
    context: Context,
    private val theme: ResolvedTheme,
    private val schema: WidgetSchema,
    current: JsonObject,
    private val legend: Legend,
    private val host: Host,
) : ScrollView(context) {

    interface Host {
        fun pickEntity(domains: List<String>, onPicked: (String) -> Unit)
        fun save(settings: JsonObject)
        fun remove()
        fun cancel()
    }

    private val values = HashMap<String, JsonElement>(current)
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val problem = TextView(context).styled(theme.type.body2, theme.colors.error)

    init {
        setBackgroundColor(theme.colors.background)
        isFillViewport = true
        val gap = theme.spacing.m.toInt()
        val side = theme.spacing.xl.toInt()
        column.setPadding(side, side, side, side)
        addView(column, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(TextView(context).styled(theme.type.h5, theme.colors.onBackground).apply { text = schema.name })
        for (field in schema.fields) {
            column.addView(space(gap))
            column.addView(TextView(context).styled(theme.type.subtitle2, theme.colors.onBackground).apply { text = field.label })
            column.addView(control(field))
        }
        column.addView(space(gap))
        column.addView(problem)
        column.addView(space(gap))
        val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(ButtonView(context, theme, "save", ::save))
        actions.addView(space(gap))
        actions.addView(ButtonView(context, theme, "remove widget") { host.remove() })
        actions.addView(space(gap))
        actions.addView(ButtonView(context, theme, "cancel") { host.cancel() })
        column.addView(actions)
    }

    private fun control(field: SchemaField) = when (field.kind) {
        FieldKind.TEXT, FieldKind.NUMBER -> EditText(context).styledInput(theme).apply {
            val value = values[field.key] ?: field.default
            setText((value as? JsonPrimitive)?.contentOrNull.orEmpty())
            if (field.kind == FieldKind.NUMBER) inputType = InputType.TYPE_CLASS_NUMBER
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) {
                    val text = s?.toString()?.trim().orEmpty()
                    when {
                        text.isEmpty() -> values.remove(field.key)
                        field.kind == FieldKind.NUMBER -> text.toIntOrNull()?.let { values[field.key] = JsonPrimitive(it) }
                        else -> values[field.key] = JsonPrimitive(text)
                    }
                }
            })
        }
        FieldKind.ENTITY -> {
            lateinit var button: ButtonView
            button = ButtonView(context, theme, entityLabel(field)) {
                host.pickEntity(field.domains) { id ->
                    values[field.key] = JsonPrimitive(id)
                    button.label = entityLabel(field)
                    button.requestLayout()
                }
            }
            button
        }
        FieldKind.CALENDARS -> toggles(field, legend.calendars)
        FieldKind.FEEDS -> toggles(field, legend.feeds)
    }

    private fun entityLabel(field: SchemaField) = (values[field.key] as? JsonPrimitive)?.contentOrNull ?: "pick an entity"

    // one toggle per source, none on means every source
    private fun toggles(field: SchemaField, ids: List<String>): LinearLayout {
        val box = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        if (ids.isEmpty()) {
            box.addView(TextView(context).styled(theme.type.body2, theme.colors.onBackground).apply { text = "none set up in sources.json" })
            return box
        }
        val chosen = (values[field.key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toMutableSet() ?: mutableSetOf()
        for (id in ids) {
            lateinit var b: ButtonView
            b = ButtonView(context, theme, legend.names[id] ?: id) {
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

    private fun save() {
        val missing = schema.fields.firstOrNull { it.required && values[it.key] == null }
        if (missing != null) {
            problem.text = "${missing.label} is needed"
            return
        }
        // a number field left at its default isn't written, so the default can change later
        val cleaned = values.filter { (k, v) ->
            val f = schema.fields.firstOrNull { it.key == k }
            f == null || f.default == null || (v as? JsonPrimitive)?.intOrNull != (f.default as? JsonPrimitive)?.intOrNull
        }
        host.save(JsonObject(cleaned))
    }

    private fun space(size: Int) = android.view.View(context).apply { layoutParams = LinearLayout.LayoutParams(size, size) }
}
