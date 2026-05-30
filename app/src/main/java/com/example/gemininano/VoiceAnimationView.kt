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

    var state: State = State.IDLE
        set(value) {
            field = value
            if (value == State.IDLE) {
                stopAnimation()
            } else {
                startAnimation()
            }
        }

    private var animator: ValueAnimator? = null
    private var animationPhase = 0f

    // Japanese Soft Pastel Colors
    private val colorSakura = Color.parseColor("#FFB7C5") // Soft Pink
    private val colorFujiBlue = Color.parseColor("#AEC6CF") // Soft Blue
    private val colorMatcha = Color.parseColor("#C1E1C1") // Soft Green
    private val colorLilac = Color.parseColor("#E6E6FA") // Soft Lilac

    private val colors = intArrayOf(colorSakura, colorFujiBlue, colorMatcha, colorLilac)
    
    // Paints for the Unified Waveform
    private val wavePaints = colors.map {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = it
            style = Paint.Style.STROKE
            strokeWidth = 12f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            setShadowLayer(20f, 0f, 0f, it) // Soft glowing effect
        }
    }

    private val wavePath = Path()
    private var currentAmplitude = 0f
    private var currentFrequency = 1f


    init {
        // Required for setShadowLayer to work properly on paths
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private fun startAnimation() {
        if (animator?.isRunning == true) return
        visibility = VISIBLE
        animator = ValueAnimator.ofFloat(0f, Math.PI.toFloat() * 2f).apply {
            duration = 3000 // Slowed down for more elegance
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                animationPhase = it.animatedValue as Float
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
        if (state == State.IDLE) return

        val cx = width / 2f
        val cy = height / 2f

        val (targetAmp, targetFreq) = when (state) {
            State.LISTENING -> Pair(height * 0.1f, 1.0f)
            State.THINKING -> Pair(height * 0.2f, 2.5f)
            State.SPEAKING -> Pair(height * 0.4f, 4.0f)
            else -> Pair(0f, 1f)
        }
        
        // Smooth interpolation for fluid state transitions
        currentAmplitude += (targetAmp - currentAmplitude) * 0.15f
        currentFrequency += (targetFreq - currentFrequency) * 0.15f

        drawUnifiedWaveform(canvas, cx, cy)
    }

    private fun drawUnifiedWaveform(canvas: Canvas, cx: Float, cy: Float) {
        val waveWidth = width * 0.8f
        val startX = cx - waveWidth / 2f
        
        val numRibbons = 4
        
        for (i in 0 until numRibbons) {
            wavePath.reset()
            val paint = wavePaints[i]
            
            val phaseOffset = i * (Math.PI / 2.5)
            val speedMult = 1f + (i * 0.15f)
            
            wavePath.moveTo(startX, cy)
            
            val steps = 100 // High resolution curve
            for (step in 0..steps) {
                val progress = step.toFloat() / steps
                val x = startX + progress * waveWidth
                
                // Smooth bell curve envelope to taper the ends to zero
                val envelope = sin(progress * Math.PI).toFloat()
                
                // The core sine wave, modulated by currentFrequency and animationPhase
                val timeFactor = animationPhase * currentFrequency * speedMult
                val wave = sin(progress * Math.PI * 4f + timeFactor + phaseOffset).toFloat()
                
                val y = cy + wave * currentAmplitude * envelope
                
                wavePath.lineTo(x, y)
            }
            
            canvas.drawPath(wavePath, paint)
        }
    }
}
