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

    // Colors: Google Assistant / Siri inspired Neon colors
    private val colorCyan = Color.parseColor("#4285F4") // Google Blue
    private val colorMagenta = Color.parseColor("#EA4335") // Google Red
    private val colorYellow = Color.parseColor("#FBBC05") // Google Yellow
    private val colorGreen = Color.parseColor("#34A853") // Google Green

    private val colors = intArrayOf(colorCyan, colorMagenta, colorYellow, colorGreen)
    
    // Paints for the Ribbon Waveform (Speaking)
    private val wavePaints = colors.map {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = it
            style = Paint.Style.STROKE
            strokeWidth = 10f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            // Soft, elegant glow
            setShadowLayer(18f, 0f, 0f, it)
        }
    }

    // Paints for the Sonar Ripple (Listening)
    private val sonarPaints = colors.map {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = it
            style = Paint.Style.STROKE
            strokeWidth = 8f
            setShadowLayer(12f, 0f, 0f, it)
        }
    }
    
    // Paints for the Infinite Flow (Thinking)
    private val flowPaints = colors.map {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = it
            style = Paint.Style.STROKE
            strokeWidth = 12f
            strokeCap = Paint.Cap.ROUND
            setShadowLayer(24f, 0f, 0f, it)
        }
    }

    private val wavePath = Path()

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

        when (state) {
            State.LISTENING -> drawSonarRipple(canvas, cx, cy)
            State.THINKING -> drawInfiniteFlow(canvas, cx, cy)
            State.SPEAKING -> drawFluidRibbons(canvas, cx, cy)
            else -> {}
        }
    }

    private fun drawSonarRipple(canvas: Canvas, cx: Float, cy: Float) {
        // Smooth concentric circles pulsing outward
        val maxRadius = height * 0.45f
        val numRings = 4
        
        for (i in 0 until numRings) {
            val paint = sonarPaints[i % sonarPaints.size]
            
            // Phase shifted for each ring so they ripple outward
            val ringPhase = (animationPhase + (i * Math.PI / 2f)) % (Math.PI * 2f)
            val normalizedProgress = (ringPhase / (Math.PI * 2f)).toFloat()
            
            val radius = maxRadius * normalizedProgress
            
            // Fade out as it expands
            val alpha = (255 * (1f - normalizedProgress)).toInt()
            paint.alpha = alpha
            
            canvas.drawCircle(cx, cy, radius, paint)
        }
    }

    private fun drawInfiniteFlow(canvas: Canvas, cx: Float, cy: Float) {
        // Sleek overlapping spinning rings
        val baseRadius = height * 0.25f
        val time = animationPhase
        
        for (i in 0 until 4) {
            val paint = flowPaints[i]
            
            // Dynamic radius and rotation
            val radiusOffset = sin(time * 2f + i).toFloat() * 15f
            val radius = baseRadius + radiusOffset
            
            val rotationSpeed = 1f + (i * 0.5f)
            val direction = if (i % 2 == 0) 1f else -1f
            val rotation = Math.toDegrees((time * rotationSpeed * direction).toDouble()).toFloat()
            
            canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(rotation)
            
            val rect = android.graphics.RectF(-radius, -radius, radius, radius)
            
            // Draw a smooth arc with dynamic length
            val sweepAngle = 90f + sin(time * 3f + i).toFloat() * 60f
            canvas.drawArc(rect, i * 90f, sweepAngle, false, paint)
            
            canvas.restore()
        }
    }

    private fun drawFluidRibbons(canvas: Canvas, cx: Float, cy: Float) {
        // 4 elegant, thick intertwined sine waves
        val maxAmplitude = height * 0.3f
        val waveWidth = width * 0.8f
        val startX = cx - waveWidth / 2f
        
        val numRibbons = 4
        
        for (i in 0 until numRibbons) {
            wavePath.reset()
            val paint = wavePaints[i]
            
            val phaseOffset = i * (Math.PI / 2f)
            val speedMult = 1.5f + (i * 0.2f)
            
            wavePath.moveTo(startX, cy)
            
            val steps = 100 // Very smooth curves
            for (step in 0..steps) {
                val progress = step.toFloat() / steps
                val x = startX + progress * waveWidth
                
                // Smooth bell curve envelope to taper the ends
                val envelope = sin(progress * Math.PI).toFloat()
                
                // Simple elegant sine wave
                val wave = sin(progress * Math.PI * 3f + (animationPhase * speedMult) + phaseOffset).toFloat()
                
                val y = cy + wave * maxAmplitude * envelope
                
                wavePath.lineTo(x, y)
            }
            
            canvas.drawPath(wavePath, paint)
        }
    }
}
