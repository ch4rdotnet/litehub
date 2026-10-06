package com.chardidathing.litehub.ui.tokens

import android.graphics.Typeface
import android.util.DisplayMetrics
import android.util.TypedValue
import com.chardidathing.litehub.core.model.Colors
import com.chardidathing.litehub.core.model.TextStyle
import com.chardidathing.litehub.core.model.Theme

// a theme turned into px and typefaces for drawing, build it off the main thread
class ResolvedTheme(theme: Theme, metrics: DisplayMetrics, fonts: Fonts, lowTier: Boolean) {

    val colors: Colors = theme.colors

    val spacing = Spacing(
        xs = dp(theme.spacing.xs, metrics),
        s = dp(theme.spacing.s, metrics),
        m = dp(theme.spacing.m, metrics),
        l = dp(theme.spacing.l, metrics),
        xl = dp(theme.spacing.xl, metrics),
    )

    val radii = Radii(
        small = dp(theme.radii.small, metrics),
        medium = dp(theme.radii.medium, metrics),
        large = dp(theme.radii.large, metrics),
    )

    val iconSize = dp(theme.iconSize, metrics)

    // zero when animations are off, so callers jump straight to the end state
    val pageSettleMs = if (theme.motion.animations && !lowTier) theme.motion.pageSettleMs else 0

    val type = with(theme.type) {
        fun r(style: TextStyle) = Text(
            size = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, style.size, metrics),
            typeface = fonts.typeface(theme.font, style.weight),
        )
        TypeScale(
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
