package com.jeffery.assistant.presence

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * The floating "she's here" avatar — a simple pulsing circle, plain View rather than
 * Compose, since hosting Compose inside a bare Service window adds a lot of lifecycle
 * plumbing for little visual benefit here. Kept intentionally simple: a soft blue
 * pulse, roughly matching the in-app orb's idle state.
 */
class NovaBubbleView(context: Context) : View(context) {

    private var pulse = 0.85f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val animator = ValueAnimator.ofFloat(0.85f, 1f).apply {
        duration = 1800
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            pulse = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        animator.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = (width.coerceAtMost(height) / 2f) * pulse

        paint.shader = RadialGradient(
            cx, cy, radius * 1.4f,
            Color.parseColor("#4D8FE8"), Color.parseColor("#004D8FE8"),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, radius * 1.4f, paint)

        paint.shader = null
        paint.color = Color.parseColor("#4D8FE8")
        canvas.drawCircle(cx, cy, radius, paint)
    }

    fun stopAnimating() {
        animator.cancel()
    }
}
