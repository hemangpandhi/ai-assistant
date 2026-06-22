package com.example.gemininano

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs

class VoiceAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class State {
        IDLE, LISTENING, THINKING, SPEAKING
    }

    private var targetState: State = State.IDLE
    private var transitionProgress: Float = 1f
    var state: State = State.IDLE
        set(value) {
            if (field == value) return
            targetState = value
            transitionProgress = 0f
            field = value
            
            if (animator?.isPaused == true) {
                animator?.resume()
            }
            if (animator?.isRunning != true) {
                startAnimation()
            }
        }

    private var animator: ValueAnimator? = null
    private var animationPhase = 0f

    // Volumetric 3D Wave Layers (Monochromatic Ice-Blue)
    private val wavePaints = Array(3) { i ->
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.BUTT // Prevents the rounded "pill" progress-bar look
            strokeWidth = when (i) {
                0 -> 10f  // Outer volumetric shadow/glow
                1 -> 4f   // Intense ice-blue mid-core
                else -> 2f // Razor-sharp white crest
            }
            when (i) {
                0 -> setShadowLayer(15f, 0f, 0f, Color.parseColor("#4D00E5FF"))
                1 -> setShadowLayer(10f, 0f, 0f, Color.parseColor("#B3A8C7FA"))
                else -> setShadowLayer(5f, 0f, 0f, Color.parseColor("#FFFFFF"))
            }
        }
    }
    
    private val wavePath = Path()

    init {
        // Hardware acceleration is fully supported for setShadowLayer on Android 9+
        // Removing LAYER_TYPE_SOFTWARE to prevent severe CPU bottlenecking and 3000ms LLM TTFT delays
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val wF = w.toFloat()
        // Seamlessly fade to invisible at the edges to prevent blocky progress-bar look
        val colors1 = intArrayOf(Color.TRANSPARENT, Color.parseColor("#4D00E5FF"), Color.parseColor("#4D00E5FF"), Color.TRANSPARENT)
        val colors2 = intArrayOf(Color.TRANSPARENT, Color.parseColor("#B3A8C7FA"), Color.parseColor("#B3A8C7FA"), Color.TRANSPARENT)
        val colors3 = intArrayOf(Color.TRANSPARENT, Color.parseColor("#FFFFFF"), Color.parseColor("#FFFFFF"), Color.TRANSPARENT)
        
        val positions = floatArrayOf(0f, 0.3f, 0.7f, 1f)
        
        wavePaints[0].shader = android.graphics.LinearGradient(0f, 0f, wF, 0f, colors1, positions, android.graphics.Shader.TileMode.CLAMP)
        wavePaints[1].shader = android.graphics.LinearGradient(0f, 0f, wF, 0f, colors2, positions, android.graphics.Shader.TileMode.CLAMP)
        wavePaints[2].shader = android.graphics.LinearGradient(0f, 0f, wF, 0f, colors3, positions, android.graphics.Shader.TileMode.CLAMP)
    }

    private fun startAnimation() {
        if (animator?.isRunning == true) return
        visibility = VISIBLE
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 45000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                animationPhase += 0.05f
                if (transitionProgress < 1f) {
                    transitionProgress += 0.05f
                    if (transitionProgress >= 1f) {
                        transitionProgress = 1f
                        state = targetState
                    }
                }
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cy = height / 2f
        val w = width.toFloat()

        for (i in 0 until 3) {
            var amplitudeModifier = 0.1f
            when (state) {
                State.LISTENING -> amplitudeModifier = 1.0f + kotlin.math.sin(animationPhase * 2f + i).toFloat() * 0.3f
                State.THINKING -> amplitudeModifier = 0.5f + kotlin.math.sin(animationPhase * 3f).toFloat() * 0.2f
                State.SPEAKING -> amplitudeModifier = 1.6f * abs(kotlin.math.sin(animationPhase * 3f + i).toFloat())
                State.IDLE -> amplitudeModifier = 0.35f + kotlin.math.sin(animationPhase * 1.5f + i).toFloat() * 0.15f
            }

            val frequency = 0.012f + (i * 0.003f)
            val baseAmplitude = 20f + (i * 4f)
            
            wavePath.reset()

            for (x in 0..width step 6) {
                val xF = x.toFloat()
                val midX = w / 2f
                val distanceFromCenter = abs(xF - midX)
                
                val gaussianDistribution = kotlin.math.exp(-(distanceFromCenter * distanceFromCenter) / (2 * (w * 0.15f) * (w * 0.15f)))
                
                val y = cy + kotlin.math.sin(xF * frequency + animationPhase + (i * 1.5f)).toFloat() * baseAmplitude * amplitudeModifier * gaussianDistribution
                
                if (x == 0) wavePath.moveTo(xF, y) else wavePath.lineTo(xF, y)
            }
            
            canvas.drawPath(wavePath, wavePaints[i])
        }
    }
}
