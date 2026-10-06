package com.nordic.tagmobile.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.max

/**
 * Tumbling 5-second oscilloscope (raw int16 full scale).
 * Window is 40_000 samples at 8 kHz. When a window completes it stays
 * visible (fading) while the next 5-second window fills from the left.
 */
class TumblingWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val windowSamples = 40_000
    private val live = ShortArray(windowSamples)
    private val held = ShortArray(windowSamples)
    private var liveFilled = 0
    private var heldValid = false
    var windowIndex = 0
        private set

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF82C0CC.toInt()
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF
        strokeWidth = 1f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt()
        textSize = 28f
    }
    private val path = Path()

    @Synchronized
    fun append(samples: ShortArray) {
        var i = 0
        while (i < samples.size) {
            val room = windowSamples - liveFilled
            val n = minOf(room, samples.size - i)
            System.arraycopy(samples, i, live, liveFilled, n)
            liveFilled += n
            i += n
            if (liveFilled >= windowSamples) {
                System.arraycopy(live, 0, held, 0, windowSamples)
                heldValid = true
                liveFilled = 0
                windowIndex++
            }
        }
        postInvalidateOnAnimation()
    }

    @Synchronized
    fun reset() {
        live.fill(0)
        held.fill(0)
        liveFilled = 0
        heldValid = false
        windowIndex = 0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        canvas.drawColor(0xFF000000.toInt())
        val mid = h / 2f
        canvas.drawLine(0f, mid, w, mid, axisPaint)

        val liveSnap: ShortArray
        val heldSnap: ShortArray
        val nFill: Int
        val win: Int
        val showHeld: Boolean
        synchronized(this) {
            liveSnap = live.copyOf()
            heldSnap = held.copyOf()
            nFill = liveFilled
            win = windowIndex
            showHeld = heldValid
        }

        val labelWin = if (nFill == 0 && showHeld && win > 0) win - 1 else win
        val startSec = labelWin * 5
        val endSec = startSec + 5
        canvas.drawText("${startSec}–${endSec} s", 16f, 36f, textPaint)

        if (nFill == 0 && !showHeld) {
            canvas.drawLine(0f, mid, w, mid, linePaint)
            return
        }

        if (showHeld && nFill < windowSamples) {
            val fade = if (nFill == 0) {
                1f
            } else {
                (1f - nFill.toFloat() / windowSamples).coerceIn(0.15f, 1f)
            }
            linePaint.alpha = (0xCC * fade).toInt().coerceIn(40, 0xCC)
            drawTrace(canvas, heldSnap, windowSamples, w, mid)
        }

        if (nFill > 0) {
            linePaint.alpha = 0xFF
            drawTrace(canvas, liveSnap, nFill, w, mid)
        } else if (showHeld) {
            linePaint.alpha = 0xFF
            drawTrace(canvas, heldSnap, windowSamples, w, mid)
        }
        linePaint.alpha = 0xFF
    }

    private fun drawTrace(
        canvas: Canvas,
        snapshot: ShortArray,
        usable: Int,
        w: Float,
        mid: Float,
    ) {
        if (usable <= 0) return
        val bins = 400
        val step = max(1, usable / bins)
        path.reset()
        var first = true
        var x = 0
        while (x < usable) {
            var peak = 0
            val end = minOf(usable, x + step)
            for (i in x until end) {
                val a = abs(snapshot[i].toInt())
                if (a > peak) peak = a
            }
            val sign = if (snapshot[x] < 0) -1 else 1
            val nx = (x.toFloat() / windowSamples) * w
            val ny = mid - (peak * sign / 32768f) * (mid - 8f)
            if (first) {
                path.moveTo(nx, ny)
                first = false
            } else {
                path.lineTo(nx, ny)
            }
            x = end
        }
        canvas.drawPath(path, linePaint)
    }
}
