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

    // Colors: Neon Cyan, Magenta, Deep Blue
    private val colorCyan = Color.parseColor("#00E5FF")
    private val colorMagenta = Color.parseColor("#D500F9")
    private val colorBlue = Color.parseColor("#2979FF")
    private val colorPurple = Color.parseColor("#7C4DFF")

    private val colors = intArrayOf(colorCyan, colorMagenta, colorBlue, colorPurple)
    
    // Paints for the Mesh Waveform
    private val wavePaints = colors.map {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = it
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            // Glow effect
            setShadowLayer(8f, 0f, 0f, it)
        }
    }

    // Paints for the Audio Spectrum Ring
    private val spectrumPaints = colors.map {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = it
            style = Paint.Style.STROKE
            strokeWidth = 4f
            strokeCap = Paint.Cap.ROUND
            setShadowLayer(10f, 0f, 0f, it)
        }
    }
    
    private val orbPaints = colors.map {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = it
            style = Paint.Style.FILL
            alpha = 150
            setShadowLayer(20f, 0f, 0f, it)
        }
    }

    private val wavePath = Path()
    private val randomArray = FloatArray(100) { (Math.random() * 2f - 1f).toFloat() }

    init {
        // Required for setShadowLayer to work properly on paths
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private fun startAnimation() {
        if (animator?.isRunning == true) return
        visibility = VISIBLE
        animator = ValueAnimator.ofFloat(0f, Math.PI.toFloat() * 2f).apply {
            duration = 2000
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
            State.LISTENING -> drawSpectrumRing(canvas, cx, cy)
            State.THINKING -> drawThinkingKnot(canvas, cx, cy)
            State.SPEAKING -> drawMeshWaveform(canvas, cx, cy)
            else -> {}
        }
    }

    private fun drawSpectrumRing(canvas: Canvas, cx: Float, cy: Float) {
        val baseRadius = height * 0.25f
        val maxBarHeight = height * 0.15f
        val numBars = 60
        
        for (i in 0 until numBars) {
            val angle = (i.toFloat() / numBars) * Math.PI * 2f
            
            // Generate some pseudo-random but continuous motion
            val timeOffset = animationPhase * 3f
            val noise = sin(angle * 5f + timeOffset) * cos(angle * 3f - timeOffset)
            val barHeight = maxBarHeight * (0.3f + 0.7f * Math.abs(noise).toFloat())
            
            val innerRadius = baseRadius
            val outerRadius = baseRadius + barHeight
            
            val startX = cx + cos(angle).toFloat() * innerRadius
            val startY = cy + sin(angle).toFloat() * innerRadius
            
            val endX = cx + cos(angle).toFloat() * outerRadius
            val endY = cy + sin(angle).toFloat() * outerRadius
            
            val paint = spectrumPaints[i % spectrumPaints.size]
            canvas.drawLine(startX, startY, endX, endY, paint)
        }
        
        // Draw an inner glowing ring
        canvas.drawCircle(cx, cy, baseRadius - 5f, wavePaints[0])
    }

    private fun drawThinkingKnot(canvas: Canvas, cx: Float, cy: Float) {
        // Futuristic segmented orbital spinner
        val maxRadius = height * 0.35f
        val time = System.currentTimeMillis() / 1000f
        
        for (i in 0 until 3) {
            // Use thicker spectrum paints for bold glowing rings
            val paint = spectrumPaints[i % spectrumPaints.size]
            val radius = maxRadius - (i * 18f)
            
            val speed = 120f + (i * 60f) // Outer ring is slowest, inner is fastest
            val direction = if (i % 2 == 1) -1f else 1f
            val rotation = (time * speed * direction) % 360f
            
            canvas.save()
            canvas.translate(cx, cy)
            canvas.rotate(rotation)
            
            val rect = android.graphics.RectF(-radius, -radius, radius, radius)
            
            // Draw 3 arc segments per ring
            for (segment in 0 until 3) {
                val startAngle = segment * 120f
                // Arcs breathe in length as they spin
                val sweepAngle = 50f + kotlin.math.sin(time * 4f + i).toFloat() * 25f 
                canvas.drawArc(rect, startAngle, sweepAngle, false, paint)
            }
            
            canvas.restore()
        }
        
        // Add a subtle, fast-pulsing inner core
        val pulse = 1f + 0.15f * kotlin.math.sin(animationPhase * 6f).toFloat()
        canvas.drawCircle(cx, cy, height * 0.08f * pulse, orbPaints[0]) // Cyan glow
        canvas.drawCircle(cx, cy, height * 0.04f * pulse, orbPaints[1]) // Magenta inner glow
    }

    private fun drawMeshWaveform(canvas: Canvas, cx: Float, cy: Float) {
        // Extremely dense, overlapping mesh waveform
        val maxAmplitude = height * 0.4f
        val waveWidth = width * 0.9f
        val startX = cx - waveWidth / 2f
        
        // We draw 8 layers to make it look dense and meshy
        val numLayers = 8
        
        for (i in 0 until numLayers) {
            wavePath.reset()
            
            // Varing phase, frequency, and speed per layer
            val phaseOffset = i * (Math.PI / 3f)
            val freqOffset = 1.5f + (i * 0.25f)
            val speedMult = 2f + (i * 0.5f)
            
            val paint = wavePaints[i % wavePaints.size]

            val currentAmplitude = maxAmplitude * (0.4f + 0.6f * sin(animationPhase * speedMult).toFloat())

            wavePath.moveTo(startX, cy)
            
            val steps = 80 // High resolution for smooth curves
            for (step in 0..steps) {
                val progress = step.toFloat() / steps
                val x = startX + progress * waveWidth
                
                // Hanning window envelope to pinch the ends of the wave
                val envelope = sin(progress * Math.PI).toFloat()
                
                // Complex wave formula combining multiple frequencies
                val wave1 = sin(progress * Math.PI * 2f * freqOffset + (animationPhase * speedMult) + phaseOffset)
                val wave2 = cos(progress * Math.PI * 4f * freqOffset - (animationPhase * speedMult * 0.5f))
                
                val combinedWave = (wave1 + wave2 * 0.5f).toFloat()
                
                val y = cy + combinedWave * currentAmplitude * envelope
                
                wavePath.lineTo(x, y)
            }
            
            canvas.drawPath(wavePath, paint)
        }
    }
}
