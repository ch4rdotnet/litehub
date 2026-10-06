package com.chardidathing.litehub.ui.tokens

import android.graphics.Typeface
import android.util.DisplayMetrics
import android.util.TypedValue
import com.chardidathing.litehub.core.model.Colors
import com.chardidathing.litehub.core.model.TextStyle
import com.chardidathing.litehub.core.model.Theme

// a theme turned into px and typefaces for drawing, build it off the main thread. compact moves
// spacing and type one step down the theme's own scale (m becomes s, body1 becomes body2), touch
// targets stay the same size, fingers don't shrink
class ResolvedTheme(
    private val theme: Theme,
    private val metrics: DisplayMetrics,
    private val fonts: Fonts,
    private val lowTier: Boolean,
    compact: Boolean = false,
) {

    // the same theme for compact pages, typefaces are cached by Fonts so this is cheap
    val compact: ResolvedTheme by lazy { if (compact) this else ResolvedTheme(theme, metrics, fonts, lowTier, compact = true) }

    val colors: Colors = theme.colors

    val palette: List<Int> = theme.palette

    val screenOff: Int = Presets.SCREEN_OFF

    val spacing = with(theme.spacing) {
        if (compact) Spacing(xs = dp(xs, metrics), s = dp(xs, metrics), m = dp(s, metrics), l = dp(m, metrics), xl = dp(l, metrics))
        else Spacing(xs = dp(xs, metrics), s = dp(s, metrics), m = dp(m, metrics), l = dp(l, metrics), xl = dp(xl, metrics))
    }

    val radii = Radii(
        small = dp(theme.radii.small, metrics),
        medium = dp(theme.radii.medium, metrics),
        large = dp(theme.radii.large, metrics),
    )

    // compact icons shrink by the same step as the text beside them
    val iconSize = dp(theme.iconSize, metrics) * if (compact) theme.type.body2.size / theme.type.body1.size else 1f

    val touchTarget = dp(theme.touchTarget, metrics)

    // zero when animations are off, so callers jump straight to the end state
    val pageSettleMs = if (theme.motion.animations && !lowTier) theme.motion.pageSettleMs else 0

    val crossfadeMs = if (theme.motion.animations && !lowTier) theme.motion.crossfadeMs else 0

    val slideMs = if (theme.motion.animations && !lowTier) theme.motion.slideMs else 0

    val type = with(theme.type) {
        fun r(style: TextStyle) = Text(
            size = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, style.size, metrics),
            typeface = fonts.typeface(theme.font, style.weight),
        )
        if (compact) TypeScale(
            h1 = r(h2), h2 = r(h3), h3 = r(h4), h4 = r(h5), h5 = r(h6), h6 = r(subtitle1),
            subtitle1 = r(subtitle2), subtitle2 = r(body2),
            body1 = r(body2), body2 = r(caption),
            button = r(button), caption = r(caption), overline = r(overline),
        )
        else TypeScale(
            h1 = r(h1), h2 = r(h2), h3 = r(h3), h4 = r(h4), h5 = r(h5), h6 = r(h6),
            subtitle1 = r(subtitle1), subtitle2 = r(subtitle2),
            body1 = r(body1), body2 = r(body2),
            button = r(button), caption = r(caption), overline = r(overline),
        )
    }

    // px
    class Spacing(val xs: Float, val s: Float, val m: Float, val l: Float, val xl: Float)

    // px
    class Radii(val small: Float, val medium: Float, val large: Float)

    // size in px
    class Text(val size: Float, val typeface: Typeface)

    class TypeScale(
        val h1: Text,
        val h2: Text,
        val h3: Text,
        val h4: Text,
        val h5: Text,
        val h6: Text,
        val subtitle1: Text,
        val subtitle2: Text,
        val body1: Text,
        val body2: Text,
        val button: Text,
        val caption: Text,
        val overline: Text,
    )

    private companion object {
        fun dp(value: Float, metrics: DisplayMetrics) =
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, metrics)
    }
}
