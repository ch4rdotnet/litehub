package com.chardidathing.litehub.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.view.MotionEvent
import android.view.View
import com.chardidathing.litehub.ui.tokens.ResolvedTheme

// a full screen numeric keypad. digits show as dots, "ok" hands the entry over, the message
// line under the title says what's wanted or what went wrong
class PinPadView(
    context: Context,
    private val theme: ResolvedTheme,
    private val title: String,
    private val onEnter: (pin: String) -> Unit,
    private val onCancel: () -> Unit,
) : View(context) {

    private val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", CANCEL, "0", OK)
    private val keyRects = keys.map { RectF() }
    private val keyBlocks = keys.map { TextBlock(maxLines = 1) }
    private val titleBlock = TextBlock(maxLines = 1)
    private val messageBlock = TextBlock(maxLines = 2)
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.colors.surface }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.colors.primary }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = theme.colors.onBackground }
    private val entry = StringBuilder()
    private var message: String? = null
    private var messageIsError = false
    private var pressed = -1
    private var titleX = 0f
    private var titleY = 0f
    private var dotsY = 0f
    private var messageY = 0f

    init {
        setBackgroundColor(theme.colors.background)
        isClickable = true
    }

    fun say(text: String, error: Boolean) {
        message = text
        messageIsError = error
        entry.setLength(0)
        layoutText()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val key = theme.touchTarget
        val gap = theme.spacing.m
        val gridWidth = key * COLUMNS + gap * (COLUMNS - 1)
        val gridHeight = key * ROWS + gap * (ROWS - 1)
        val headHeight = theme.touchTarget * HEAD_TARGETS
        val top = ((h - headHeight - gridHeight) / 2).coerceAtLeast(theme.spacing.m)
        titleX = (w - gridWidth) / 2
        titleY = top
        dotsY = top + headHeight / 2
        messageY = top + headHeight * 2 / 3
        val left = (w - gridWidth) / 2
        val gridTop = top + headHeight
        keys.forEachIndexed { i, label ->
            val col = i % COLUMNS
            val row = i / COLUMNS
            val x = left + col * (key + gap)
            val y = gridTop + row * (key + gap)
            keyRects[i].set(x, y, x + key, y + key)
            keyBlocks[i].set(label, theme.type.h5, theme.colors.onSurface, key.toInt(), Layout.Alignment.ALIGN_CENTER)
        }
        layoutText()
    }

    private fun layoutText() {
        val width = (keyRects.lastOrNull()?.right ?: 0f) - titleX
        titleBlock.set(title, theme.type.h5, theme.colors.onBackground, width.toInt(), Layout.Alignment.ALIGN_CENTER)
        val color = if (messageIsError) theme.colors.error else theme.colors.onBackground
        messageBlock.set(message.orEmpty(), theme.type.body2, color, width.toInt(), Layout.Alignment.ALIGN_CENTER)
    }

    override fun onDraw(canvas: Canvas) {
        titleBlock.draw(canvas, titleX, titleY)
        // one dot per digit typed, centred under the title
        val r = theme.spacing.s / 2
        val step = theme.spacing.m
        var x = width / 2f - (entry.length - 1) * step / 2
        repeat(entry.length) {
            canvas.drawCircle(x, dotsY, r, dotPaint)
            x += step
        }
        messageBlock.draw(canvas, titleX, messageY)
        val radius = theme.radii.medium
        keyRects.forEachIndexed { i, rect ->
            canvas.drawRoundRect(rect, radius, radius, if (i == pressed) pressedPaint else keyPaint)
            val text = keyBlocks[i]
            text.draw(canvas, rect.left, rect.centerY() - text.height / 2f)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val hit = keyRects.indexOfFirst { it.contains(event.x, event.y) }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> setPressedKey(hit)
            MotionEvent.ACTION_MOVE -> if (hit != pressed) setPressedKey(-1)
            MotionEvent.ACTION_UP -> {
                val key = pressed
                setPressedKey(-1)
                if (key >= 0 && key == hit) press(keys[key])
            }
            MotionEvent.ACTION_CANCEL -> setPressedKey(-1)
        }
        return true
    }

    private fun press(key: String) {
        when (key) {
            CANCEL -> onCancel()
            OK -> {
                val pin = entry.toString()
                entry.setLength(0)
                invalidate()
                onEnter(pin)
            }
            else -> if (entry.length < MAX_DIGITS) {
                entry.append(key)
                invalidate()
            }
        }
    }

    private fun setPressedKey(i: Int) {
        if (i == pressed) return
        pressed = i
        invalidate()
    }

    private companion object {
        const val COLUMNS = 3
        const val ROWS = 4
        // title, dots and message share this many touch targets of height above the keys
        const val HEAD_TARGETS = 2
        const val MAX_DIGITS = 12
        const val CANCEL = "back"
        const val OK = "ok"
    }
}
