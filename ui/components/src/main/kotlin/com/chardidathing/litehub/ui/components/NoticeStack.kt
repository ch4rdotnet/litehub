package com.chardidathing.litehub.ui.components

import android.animation.LayoutTransition
import android.annotation.SuppressLint
import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import com.chardidathing.litehub.ui.tokens.Easing
import com.chardidathing.litehub.ui.tokens.ResolvedTheme

// notice cards stacked down from the top, newest first. each one leaves on its own after holdMs,
// and past max the oldest is pushed out to make room
@SuppressLint("ViewConstructor")
class NoticeStack(context: Context, private val theme: ResolvedTheme, private val max: Int, private val holdMs: Long) : LinearLayout(context) {

    private val leaving = mutableSetOf<NoticeView>()

    init {
        orientation = VERTICAL
        gravity = Gravity.END
        // we slide cards in and out ourselves, the transition only moves the rest up and down
        if (theme.slideMs > 0) layoutTransition = LayoutTransition().apply {
            disableTransitionType(LayoutTransition.APPEARING)
            disableTransitionType(LayoutTransition.DISAPPEARING)
            for (type in listOf(LayoutTransition.CHANGE_APPEARING, LayoutTransition.CHANGE_DISAPPEARING)) {
                setDuration(type, theme.slideMs.toLong())
                setStartDelay(type, 0)
                setInterpolator(type, Easing.settle(0f))
            }
        }
    }

    fun push(title: String?, message: String) {
        lateinit var card: NoticeView
        card = NoticeView(context, theme, title, message) { dismiss(card) }
        addView(card, 0, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = theme.spacing.s.toInt() })
        card.slideIn()
        postDelayed({ dismiss(card) }, holdMs)
        // the oldest live cards sit at the bottom
        (0 until childCount).map { getChildAt(it) as NoticeView }.filterNot(leaving::contains).drop(max).forEach(::dismiss)
    }

    private fun dismiss(card: NoticeView) {
        if (card.parent != this || !leaving.add(card)) return
        card.slideOut {
            leaving.remove(card)
            removeView(card)
        }
    }
}
