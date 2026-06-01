package com.example.gemininano

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

class VoiceAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class State {
        IDLE, LISTENING, THINKING, SPEAKING
    }

    private var currentState = State.IDLE
    private var targetState = State.IDLE
    private var transitionProgress = 1f

    var state: State = State.IDLE
        set(value) {
            if (field == value) return
            currentState = field
            targetState = value
            transitionProgress = 0f
            field = value
            if (value != State.IDLE && animator?.isRunning != true) {
                startAnimation()
            }
        }

    private var animator: ValueAnimator? = null
    private var animationPhase = 0f

    // Google Assistant Colors
    private val googleBlue = Color.parseColor("#4285F4")
    private val googleRed = Color.parseColor("#EA4335")
    private val googleYellow = Color.parseColor("#FBBC05")
    private val googleGreen = Color.parseColor("#34A853")
    
    private val dotColors = intArrayOf(googleBlue, googleRed, googleYellow, googleGreen)

    // General Paint Setup
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private fun startAnimation() {
        if (animator?.isRunning == true) return
        visibility = VISIBLE
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 10000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                animationPhase += 0.1f
                if (transitionProgress < 1f) {
                    transitionProgress += 0.08f
                    if (transitionProgress >= 1f) {
                        transitionProgress = 1f
                        currentState = targetState
                        if (currentState == State.IDLE) {
                            stopAnimation()
                        }
                    }
                }
                invalidate()
            }
            start()
        }
    }

    private fun stopAnimation() {
        animator?.cancel()
        animator = null
        visibility = GONE
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val cx = width / 2f
        val cy = height / 2f

        if (transitionProgress < 1f) {
            if (currentState != State.IDLE) drawState(currentState, canvas, cx, cy, 1f - transitionProgress)
            if (targetState != State.IDLE) drawState(targetState, canvas, cx, cy, transitionProgress)
        } else {
            if (currentState != State.IDLE) drawState(currentState, canvas, cx, cy, 1f)
        }
    }

    private fun drawState(state: State, canvas: Canvas, cx: Float, cy: Float, alphaProgress: Float) {
        val baseAlpha = (255 * alphaProgress).toInt().coerceIn(0, 255)
        if (baseAlpha == 0) return

        val maxRadius = Math.min(width / 12f, height / 4f)
        val dotRadius = maxRadius * 0.6f
        val spacing = dotRadius * 3.5f
        val startX = cx - (spacing * 1.5f)

        for (i in 0 until 4) {
            paint.color = dotColors[i]
            paint.alpha = baseAlpha
            
            var x = startX + (i * spacing)
            var y = cy
            var r = dotRadius

            when (state) {
                State.LISTENING -> {
                    // Gentle pulse together
                    val pulse = sin(animationPhase * 0.5f).toFloat()
                    r += pulse * (dotRadius * 0.2f)
                }
                State.THINKING -> {
                    // Wave motion left to right
                    val wave = sin(animationPhase * 1.5f + (i * 1.2f)).toFloat()
                    y += wave * (dotRadius * 0.8f)
                }
                State.SPEAKING -> {
                    // Random-looking EQ bounce (combining sine waves)
                    val bounce = sin(animationPhase * 2f + (i * 2f)).toFloat() * cos(animationPhase * 1.3f + i).toFloat()
                    r += bounce * (dotRadius * 0.4f)
                }
                State.IDLE -> {}
            }

            canvas.drawCircle(x, y, r, paint)
        }
    }
}
