package com.chardidathing.litehub.ui.tokens

import com.chardidathing.litehub.core.model.Colors
import com.chardidathing.litehub.core.model.Motion
import com.chardidathing.litehub.core.model.Radii
import com.chardidathing.litehub.core.model.Spacing
import com.chardidathing.litehub.core.model.TextStyle
import com.chardidathing.litehub.core.model.Theme
import com.chardidathing.litehub.core.model.TypeScale

// the only place literal design values live, user themes extend one of these
object Presets {

    private val spacing = Spacing(xs = 4f, s = 8f, m = 16f, l = 24f, xl = 32f)

    // md2 shape defaults, large is 0 on purpose
    private val radii = Radii(small = 4f, medium = 4f, large = 0f)

    // md2 says 24, a wall panel is read from further away
    private val iconSize = 32f

    // md2 asks for 48, a wall panel gets tapped standing up
    private val touchTarget = 64f

    // the longest a page settle may take, a full 1920px page from rest
    // a photo frame crossfade is slow on purpose, it should read as calm not as a transition
    // panels and banners sliding in, quick enough not to hold anything up
    private val motion = Motion(animations = true, pageSettleMs = 300, crossfadeMs = 1500, slideMs = 250)

    // md2 palette, 500s on light and 200s on dark so they read against either surface
    private val paletteLight = listOf(
        0xFF6200EE, 0xFF03DAC6, 0xFFF44336, 0xFFFF9800, 0xFF4CAF50, 0xFF2196F3, 0xFFE91E63, 0xFF795548,
    ).map { it.toInt() }

    private val paletteDark = listOf(
        0xFFBB86FC, 0xFF03DAC6, 0xFFEF9A9A, 0xFFFFCC80, 0xFFA5D6A7, 0xFF90CAF9, 0xFFF48FB1, 0xFFBCAAA4,
    ).map { it.toInt() }

    // md2 type scale, light (300) falls back to the nearest bundled weight
    private val type = TypeScale(
        h1 = TextStyle(size = 96f, weight = 300),
        h2 = TextStyle(size = 60f, weight = 300),
        h3 = TextStyle(size = 48f, weight = 400),
        h4 = TextStyle(size = 34f, weight = 400),
        h5 = TextStyle(size = 24f, weight = 400),
        h6 = TextStyle(size = 20f, weight = 500),
        subtitle1 = TextStyle(size = 16f, weight = 400),
        subtitle2 = TextStyle(size = 14f, weight = 500),
        body1 = TextStyle(size = 16f, weight = 400),
        body2 = TextStyle(size = 14f, weight = 400),
        button = TextStyle(size = 14f, weight = 500),
        caption = TextStyle(size = 12f, weight = 400),
        overline = TextStyle(size = 10f, weight = 400),
    )

    val md2Light = Theme(
        id = "md2-light",
        name = "md2 light",
        colors = Colors(
            primary = 0xFF6200EE.toInt(),
            primaryVariant = 0xFF3700B3.toInt(),
            secondary = 0xFF03DAC6.toInt(),
            secondaryVariant = 0xFF018786.toInt(),
            // grey 100 rather than white, we have no elevation shadows to lift tiles off it
            background = 0xFFF5F5F5.toInt(),
            surface = 0xFFFFFFFF.toInt(),
            error = 0xFFB00020.toInt(),
            onPrimary = 0xFFFFFFFF.toInt(),
            onSecondary = 0xFF000000.toInt(),
            onBackground = 0xFF000000.toInt(),
            onSurface = 0xFF000000.toInt(),
            onError = 0xFFFFFFFF.toInt(),
        ),
        palette = paletteLight,
        spacing = spacing,
        radii = radii,
        iconSize = iconSize,
        touchTarget = touchTarget,
        font = Fonts.LEXEND,
        type = type,
        motion = motion,
    )

    val md2Dark = Theme(
        id = "md2-dark",
        name = "md2 dark",
        colors = Colors(
            primary = 0xFFBB86FC.toInt(),
            primaryVariant = 0xFF3700B3.toInt(),
            secondary = 0xFF03DAC6.toInt(),
            secondaryVariant = 0xFF03DAC6.toInt(),
            background = 0xFF121212.toInt(),
            // md2's 1dp elevation overlay baked in, no runtime overlays
            surface = 0xFF1E1E1E.toInt(),
            error = 0xFFCF6679.toInt(),
            onPrimary = 0xFF000000.toInt(),
            onSecondary = 0xFF000000.toInt(),
            onBackground = 0xFFFFFFFF.toInt(),
            onSurface = 0xFFFFFFFF.toInt(),
            onError = 0xFF000000.toInt(),
        ),
        palette = paletteDark,
        spacing = spacing,
        radii = radii,
        iconSize = iconSize,
        touchTarget = touchTarget,
        font = Fonts.LEXEND,
        type = type,
        motion = motion,
    )

    // #000000 background, black pixels are off on oled so only tiles and text light up
    val oled = md2Dark.copy(
        id = "oled",
        name = "oled black",
        colors = md2Dark.colors.copy(
            background = 0xFF000000.toInt(),
            surface = 0xFF121212.toInt(),
        ),
    )

    val all = listOf(md2Light, md2Dark, oled)

    // what a "screen off" overlay draws, black pixels are dark on lcd and off on oled
    const val SCREEN_OFF = 0xFF000000.toInt()

    // around a video that doesn't fill the screen, any other colour shows up as a frame
    const val LETTERBOX = 0xFF000000.toInt()

    // md2's 32% black scrim, dims the page under the shade so the two don't run together
    const val SCRIM = 0x52000000

    // used when there's no usable config to pick a theme from
    val fallbackLight = md2Light
    val fallbackDark = md2Dark
}
