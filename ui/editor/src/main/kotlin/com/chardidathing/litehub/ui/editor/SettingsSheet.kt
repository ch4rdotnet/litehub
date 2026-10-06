package com.chardidathing.litehub.ui.editor

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.chardidathing.litehub.core.model.WidgetSchema
import com.chardidathing.litehub.ui.components.ButtonView
import com.chardidathing.litehub.ui.tokens.ResolvedTheme
import com.chardidathing.litehub.ui.widgets.Legend
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
        column.addView(FieldForm(context, theme, schema.fields, values, legend, host::pickEntity))
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
