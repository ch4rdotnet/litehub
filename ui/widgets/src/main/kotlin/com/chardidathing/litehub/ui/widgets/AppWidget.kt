package com.chardidathing.litehub.ui.widgets

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.chardidathing.litehub.ui.components.TextBlock
import com.chardidathing.litehub.ui.components.WidgetView
import com.chardidathing.litehub.ui.tokens.ResolvedTheme
import kotlinx.serialization.Serializable

// app is the drawer's key for it, the package (with the profile after an @ for work apps)
@Serializable
data class AppConfig(val app: String, val name: String? = null)

// one app's icon and name, tap to open it. the binder looks the app up, this only draws
@SuppressLint("ViewConstructor")
class AppWidget(context: Context, theme: ResolvedTheme, val config: AppConfig) : WidgetView(context, theme) {

    var onTap: (() -> Unit)? = null
        set(value) {
            field = value
            isClickable = value != null
        }

    // px, square, what the binder should draw the icon at
    val iconSize = theme.touchTarget.toInt()

    private var label: String? = null
    private var icon: Bitmap? = null
    private var missing = false
    private val nameBlock = TextBlock(maxLines = 2)
    private val noteBlock = TextBlock(maxLines = 2)
    private val iconPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        setOnClickListener { onTap?.invoke() }
    }

    // label null is an app that isn't installed here
    fun show(label: String?, icon: Bitmap?) {
        this.label = label
        this.icon = icon
        missing = label == null
        onContentChanged()
        invalidate()
    }

    override fun onContentChanged() {
        val w = content.width().toInt()
        nameBlock.set(config.name ?: label ?: config.app, theme.type.h6, theme.colors.onSurface, w)
        noteBlock.set(if (missing) "not installed on this device" else "", theme.type.body2, theme.colors.error, w)
    }

    override fun drawContent(canvas: Canvas) {
        icon?.let { canvas.drawBitmap(it, content.left, content.top, iconPaint) }
        val nameTop = content.top + iconSize + theme.spacing.s
        nameBlock.draw(canvas, content.left, nameTop)
        noteBlock.draw(canvas, content.left, nameTop + nameBlock.height + theme.spacing.xs)
    }
}
