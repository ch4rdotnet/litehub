package com.chardidathing.litehub.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ThemeSelection(
    val mode: ThemeMode,
    val light: String,
    val dark: String,
)

@Serializable
enum class ThemeMode {
    @SerialName("system") SYSTEM,
    @SerialName("light") LIGHT,
    @SerialName("dark") DARK,
}

@Serializable
data class Theme(
    val id: String,
    val name: String,
    val colors: Colors,
    // categorical colours handed out to calendars (and the like) by position
    val palette: List<Argb>,
    val spacing: Spacing,
    val radii: Radii,
    // dp, square
    val iconSize: Float,
    // dp, the smallest thing a finger is asked to hit (keypads, menu rows)
    val touchTarget: Float,
    val font: String,
    val type: TypeScale,
    val motion: Motion,
)

// animations are opt-in per theme, and low tier devices turn them off regardless
@Serializable
data class Motion(val animations: Boolean, val pageSettleMs: Int, val crossfadeMs: Int, val slideMs: Int)

// md2 colour roles
@Serializable
data class Colors(
    val primary: Argb,
    val primaryVariant: Argb,
    val secondary: Argb,
    val secondaryVariant: Argb,
    val background: Argb,
    val surface: Argb,
    val error: Argb,
    val onPrimary: Argb,
    val onSecondary: Argb,
    val onBackground: Argb,
    val onSurface: Argb,
    val onError: Argb,
)

// dp
@Serializable
data class Spacing(val xs: Float, val s: Float, val m: Float, val l: Float, val xl: Float)

// dp
@Serializable
data class Radii(val small: Float, val medium: Float, val large: Float)

// md2 type scale names
@Serializable
data class TypeScale(
    val h1: TextStyle,
    val h2: TextStyle,
    val h3: TextStyle,
    val h4: TextStyle,
    val h5: TextStyle,
    val h6: TextStyle,
    val subtitle1: TextStyle,
    val subtitle2: TextStyle,
    val body1: TextStyle,
    val body2: TextStyle,
    val button: TextStyle,
    val caption: TextStyle,
    val overline: TextStyle,
)

// size in sp, weight is css style (400 regular, 500 medium)
@Serializable
data class TextStyle(val size: Float, val weight: Int)
