package com.chardidathing.litehub.ui.widgets

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.chardidathing.litehub.core.model.Entity
import com.chardidathing.litehub.core.model.EntitySnapshot
import com.chardidathing.litehub.ui.components.ButtonView
import com.chardidathing.litehub.ui.components.SliderView
import com.chardidathing.litehub.ui.components.WrapRow
import com.chardidathing.litehub.ui.components.styled
import com.chardidathing.litehub.ui.tokens.ResolvedTheme
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

// everything a light can do beyond on and off, shown for what this one supports (brightness,
// warmth, colour, effects). it only gets snapshots, and hands back the service call to make
@SuppressLint("ViewConstructor")
class LightControls(
    context: Context,
    private val theme: ResolvedTheme,
    private val id: String,
    private val send: (service: String, data: JsonObject) -> Unit,
    onClose: () -> Unit,
) : LinearLayout(context) {

    private val name = TextView(context).styled(theme.type.h6, theme.colors.onBackground)
    private val state = TextView(context).styled(theme.type.body2, theme.colors.onBackground)
    private val power = ButtonView(context, theme, "on") { send(if (on) "turn_off" else "turn_on", JsonObject(emptyMap())) }

    private val brightnessLabel = label()
    private val brightness = SliderView(context, theme, onMove = { brightnessLabel.text = "brightness ${percent(it)}%" }) {
        send("turn_on", buildJsonObject { put("brightness_pct", percent(it).coerceAtLeast(1)) })
    }
    private val warmthLabel = label()
    private val warmth = SliderView(context, theme, onMove = { warmthLabel.text = "warmth ${kelvin(it)}k" }) {
        send("turn_on", buildJsonObject { put("color_temp_kelvin", kelvin(it)) })
    }
    private val hue: SliderView = SliderView(context, theme, onMove = { saturation.colors = intArrayOf(Color.WHITE, hueColor(it)) }) { sendColour() }
    private val saturationLabel = label()
    private val saturation: SliderView = SliderView(context, theme, onMove = { saturationLabel.text = "saturation ${percent(it)}%" }) { sendColour() }
    private val effectsLabel = label().apply { text = "effects" }
    private val effects = WrapRow(context, theme.spacing.s)
    private val problem = TextView(context).styled(theme.type.body2, theme.colors.error)

    private val brightnessRow = section(brightnessLabel, brightness)
    private val warmthRow = section(warmthLabel, warmth)
    private val colourRow = section(label().apply { text = "colour" }, hue, saturationLabel, saturation)
    private val effectsRow = section(effectsLabel, effects)

    private var on = false
    private var minKelvin = DEFAULT_MIN_KELVIN
    private var maxKelvin = DEFAULT_MAX_KELVIN
    private var effectNames: List<String> = emptyList()
    private val effectButtons = ArrayList<ButtonView>()

    init {
        orientation = VERTICAL
        val head = LinearLayout(context).apply { orientation = VERTICAL }
        head.addView(name)
        head.addView(state)
        val top = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        top.addView(head, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(power)
        addView(top)
        addView(brightnessRow)
        addView(warmthRow)
        addView(colourRow)
        addView(effectsRow)
        addView(problem)
        addView(space())
        addView(ButtonView(context, theme, "close", onClose), LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        hue.colors = IntArray(HUE_STOPS + 1) { hueColor(it / HUE_STOPS.toFloat()) }
        warmth.colors = IntArray(WARMTH_STOPS + 1) { warmthColor(it / WARMTH_STOPS.toFloat()) }
        show(EntitySnapshot.Connecting)
    }

    fun show(snapshot: EntitySnapshot) {
        val entity = when (snapshot) {
            is EntitySnapshot.Live -> snapshot.entity
            is EntitySnapshot.Stale -> snapshot.entity
            else -> null
        }
        name.text = entity?.attribute("friendly_name") ?: id
        state.text = entity?.let(EntityStates::describe) ?: ""
        problem.text = when (snapshot) {
            EntitySnapshot.Connecting -> "connecting"
            EntitySnapshot.NotFound -> "not found in home assistant"
            is EntitySnapshot.Failed -> snapshot.reason
            is EntitySnapshot.Live -> snapshot.error.orEmpty()
            is EntitySnapshot.Stale -> snapshot.error ?: snapshot.reason
        }
        problem.visibility = if (problem.text.isEmpty()) GONE else VISIBLE
        on = entity?.state == "on"
        power.label = if (on) "on" else "off"
        power.checked = on
        power.requestLayout()
        if (entity != null) fill(entity)
    }

    private fun fill(e: Entity) {
        val modes = strings(e.attributes["supported_color_modes"])
        val colour = modes.any { it in COLOUR_MODES }
        brightnessRow.visibility = if (modes.any { it != "onoff" && it != "unknown" }) VISIBLE else GONE
        warmthRow.visibility = if ("color_temp" in modes) VISIBLE else GONE
        colourRow.visibility = if (colour) VISIBLE else GONE

        // ha leaves brightness out while a light is off, the slider sits at the bottom
        val b = number(e, "brightness")?.div(MAX_BRIGHTNESS)?.toFloat() ?: 0f
        brightness.value = b
        brightnessLabel.text = "brightness ${percent(brightness.value)}%"

        // older ha only has mireds, which run the other way
        minKelvin = number(e, "min_color_temp_kelvin")?.toInt() ?: number(e, "max_mireds")?.let { (MIREDS / it).roundToInt() } ?: DEFAULT_MIN_KELVIN
        maxKelvin = number(e, "max_color_temp_kelvin")?.toInt() ?: number(e, "min_mireds")?.let { (MIREDS / it).roundToInt() } ?: DEFAULT_MAX_KELVIN
        number(e, "color_temp_kelvin")?.let { k -> warmth.value = ((k - minKelvin) / (maxKelvin - minKelvin).coerceAtLeast(1)).toFloat() }
        warmthLabel.text = if (e.attribute("color_mode") == "color_temp") "warmth ${kelvin(warmth.value)}k" else "warmth"

        val hs = (e.attributes["hs_color"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
        if (hs != null && hs.size == 2) {
            hue.value = (hs[0] / FULL_TURN).toFloat()
            saturation.value = (hs[1] / PERCENT).toFloat()
        }
        saturation.colors = intArrayOf(Color.WHITE, hueColor(hue.value))
        saturationLabel.text = "saturation ${percent(saturation.value)}%"

        val names = strings(e.attributes["effect_list"])
        if (names != effectNames) {
            effectNames = names
            effects.removeAllViews()
            effectButtons.clear()
            for (n in names) {
                val button = ButtonView(context, theme, n) { send("turn_on", buildJsonObject { put("effect", n) }) }
                effectButtons += button
                effects.addView(button)
            }
        }
        val current = e.attribute("effect")
        effectButtons.forEachIndexed { i, button -> button.checked = names[i] == current }
        effectsRow.visibility = if (names.isEmpty()) GONE else VISIBLE
    }

    private fun sendColour() = send(
        "turn_on",
        buildJsonObject {
            putJsonArray("hs_color") {
                add(JsonPrimitive((hue.value * FULL_TURN).roundToInt()))
                add(JsonPrimitive(percent(saturation.value)))
            }
        },
    )

    private fun percent(v: Float) = (v * PERCENT).roundToInt()

    private fun kelvin(v: Float) = (minKelvin + v * (maxKelvin - minKelvin)).roundToInt()

    private fun warmthColor(v: Float) = blackbody(DEFAULT_MIN_KELVIN + v * (DEFAULT_MAX_KELVIN - DEFAULT_MIN_KELVIN))

    private fun hueColor(v: Float) = Color.HSVToColor(floatArrayOf(v * FULL_TURN, 1f, 1f))

    private fun number(e: Entity, key: String) = (e.attributes[key] as? JsonPrimitive)?.doubleOrNull

    private fun strings(element: Any?) = (element as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

    private fun label() = TextView(context).styled(theme.type.subtitle2, theme.colors.onBackground)

    private fun section(vararg views: View) = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(space())
        views.forEach { addView(it, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
    }

    private fun space() = View(context).apply { layoutParams = LayoutParams(0, theme.spacing.m.toInt()) }

    companion object {
        // the lights this panel knows how to drive
        fun supports(id: String) = id.substringBefore('.') == "light"

        private val COLOUR_MODES = setOf("hs", "xy", "rgb", "rgbw", "rgbww")
        private const val MAX_BRIGHTNESS = 255.0
        private const val PERCENT = 100f
        private const val FULL_TURN = 360f
        private const val MIREDS = 1_000_000.0
        // ha's own defaults for a light that doesn't say
        private const val DEFAULT_MIN_KELVIN = 2000
        private const val DEFAULT_MAX_KELVIN = 6500
        private const val HUE_STOPS = 6
        private const val WARMTH_STOPS = 4

        // tanner helland's fit of blackbody colour, close enough to show warm against cool
        private fun blackbody(k: Float): Int {
            val t = k / 100f
            val r = if (t <= 66f) 255f else 329.69873f * (t - 60f).pow(-0.13320476f)
            val g = if (t <= 66f) 99.4708f * ln(t) - 161.11957f else 288.12216f * (t - 60f).pow(-0.07551485f)
            val b = when {
                t >= 66f -> 255f
                t <= 19f -> 0f
                else -> 138.51773f * ln(t - 10f) - 305.04479f
            }
            return Color.rgb(r.coerceIn(0f, 255f).toInt(), g.coerceIn(0f, 255f).toInt(), b.coerceIn(0f, 255f).toInt())
        }
    }
}
