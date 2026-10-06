package com.nordic.tagmobile.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import com.nordic.tagmobile.R
/**
 * nRF Connect–style RSSI bars (3 bars + color by strength), matching preview_home.html.
 * Bars animate when RSSI level changes.
 */
class RssiBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val inactive = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.border_strong)
    }
    private val active = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var level = 0
    private var animProgress = 1f
    private var animator: ValueAnimator? = null

    private val barWidth = dp(5f)
    private val gap = dp(3f)
    private val radii = floatArrayOf(dp(2f), dp(2f), dp(2f), dp(2f), dp(2f), dp(2f), dp(2f), dp(2f))

    fun setRssi(rssi: Int, animate: Boolean = true) {
        val next = when {
            rssi >= -70 -> 3
            rssi >= -85 -> 2
            rssi > -999 -> 1
            else -> 0
        }
        val color = when {
            rssi >= -70 -> ContextCompat.getColor(context, R.color.primary)
            rssi >= -85 -> ContextCompat.getColor(context, R.color.primary_mid)
            else -> ContextCompat.getColor(context, R.color.accent)
        }
        active.color = color
        if (next == level && !animate) {
            invalidate()
            return
        }
        val changed = next != level
        level = next
        if (animate && changed) {
            animator?.cancel()
            animProgress = 0f
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 220
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    animProgress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            animProgress = 1f
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (barWidth * 3 + gap * 2).toInt()
        val h = resolveSize(dp(18f).toInt(), heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val heights = floatArrayOf(h * 0.35f, h * 0.65f, h)
        for (i in 0 until 3) {
            val left = i * (barWidth + gap)
            val barH = heights[i]
            val top = h - barH
            rect.set(left, top, left + barWidth, h)
            val paint = if (i < level) {
                val alpha = (55 + 200 * animProgress).toInt().coerceIn(0, 255)
                active.alpha = alpha
                active
            } else {
                inactive
            }
            canvas.drawRoundRect(rect, radii[0], radii[0], paint)
        }
        active.alpha = 255
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}
