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

    // Colors
    private val colorCyan = Color.parseColor("#00F2FE")
    private val colorBlue = Color.parseColor("#4FACFE")
    private val colorYellow = Color.parseColor("#F093FB")
    private val colorRed = Color.parseColor("#F5576C")
    private val colorGreen = Color.parseColor("#43E97B")

    // General Paint Setup
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val wavePath = Path()

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
                animationPhase += 0.08f
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

        when (state) {
            State.LISTENING -> drawListening(canvas, cx, cy, baseAlpha)
            State.THINKING -> drawThinking(canvas, cx, cy, baseAlpha)
            State.SPEAKING -> drawSpeaking(canvas, cx, cy, baseAlpha)
            State.IDLE -> {}
        }
    }

    // ==========================================
    // STATE 1: LISTENING (EQ Bars + Glowing Ring)
    // ==========================================
    private fun drawListening(canvas: Canvas, cx: Float, cy: Float, alpha: Int) {
        // Draw center ring
        val ringRadius = height * 0.45f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 8f
        paint.setShadowLayer(20f, 0f, 0f, colorCyan)
        paint.color = Color.argb(alpha, Color.red(colorCyan), Color.green(colorCyan), Color.blue(colorCyan))
        
        // Slight pulse on the ring
        val pulse = 1f + sin(animationPhase * 2f).toFloat() * 0.05f
        canvas.drawCircle(cx, cy, ringRadius * pulse, paint)
        paint.clearShadowLayer()

        // Draw EQ Bars
        paint.style = Paint.Style.FILL
        val barWidth = 10f
        val barSpacing = 24f
        val numBarsSide = (cx - ringRadius - 60f) / barSpacing
        
        val maxEqHeight = height * 0.4f

        for (i in 0 until numBarsSide.toInt()) {
            // Left side (Cyan to Blue)
            val leftX = cx - ringRadius - 40f - (i * barSpacing)
            val leftProgress = i.toFloat() / numBarsSide
            
            // Random-looking math wave for EQ
            val leftHeight = maxEqHeight * (0.2f + 0.8f * Math.pow(sin(animationPhase * 3f + i * 0.5f).toDouble(), 2.0).toFloat()) * (1f - leftProgress)
            
            paint.color = blendColors(colorCyan, colorBlue, leftProgress)
            paint.alpha = alpha
            canvas.drawRoundRect(leftX - barWidth/2, cy - leftHeight, leftX + barWidth/2, cy + leftHeight, barWidth/2, barWidth/2, paint)

            // Right side (Yellow to Red)
            val rightX = cx + ringRadius + 40f + (i * barSpacing)
            val rightHeight = maxEqHeight * (0.2f + 0.8f * Math.pow(cos(animationPhase * 2.5f - i * 0.4f).toDouble(), 2.0).toFloat()) * (1f - leftProgress)
            
            paint.color = blendColors(colorYellow, colorRed, leftProgress)
            paint.alpha = alpha
            canvas.drawRoundRect(rightX - barWidth/2, cy - rightHeight, rightX + barWidth/2, cy + rightHeight, barWidth/2, barWidth/2, paint)
        }
    }

    // ==========================================
    // STATE 2: THINKING (HUD Spinner)
    // ==========================================
    private fun drawThinking(canvas: Canvas, cx: Float, cy: Float, alpha: Int) {
        val radius = height * 0.35f
        paint.style = Paint.Style.STROKE
        val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        val innerRect = RectF(cx - radius*0.7f, cy - radius*0.7f, cx + radius*0.7f, cy + radius*0.7f)
        val outerRect = RectF(cx - radius*1.3f, cy - radius*1.3f, cx + radius*1.3f, cy + radius*1.3f)

        paint.color = Color.argb(alpha, Color.red(colorBlue), Color.green(colorBlue), Color.blue(colorBlue))
        
        // Inner arc rotating fast
        paint.strokeWidth = 12f
        val phaseDeg1 = Math.toDegrees(animationPhase.toDouble() * 2.0).toFloat()
        canvas.drawArc(innerRect, phaseDeg1, 140f, false, paint)
        canvas.drawArc(innerRect, phaseDeg1 + 180f, 140f, false, paint)

        // Middle arc rotating opposite
        paint.strokeWidth = 16f
        paint.color = Color.argb(alpha, 80, 80, 80) // Darker segment
        val phaseDeg2 = Math.toDegrees(-animationPhase.toDouble() * 1.5).toFloat()
        canvas.drawArc(rect, phaseDeg2, 220f, false, paint)

        // Outer arc small dots
        paint.strokeWidth = 6f
        paint.color = Color.argb(alpha, Color.red(colorCyan), Color.green(colorCyan), Color.blue(colorCyan))
        val phaseDeg3 = Math.toDegrees(animationPhase.toDouble() * 1.0).toFloat()
        for (i in 0..3) {
            canvas.drawArc(outerRect, phaseDeg3 + (i * 90f), 20f, false, paint)
        }

        // Abstract HUD Elements on sides
        paint.style = Paint.Style.FILL
        paint.strokeWidth = 4f
        val hudStartX = cx - radius * 2f
        val hudEndX = cx - radius * 1.5f
        
        // Left HUD Lines
        for(i in 0..4) {
            val yOffset = (i - 2) * 15f
            val lineWidth = 40f + sin(animationPhase * 2f + i).toFloat() * 20f
            canvas.drawLine(hudStartX - lineWidth, cy + yOffset, hudStartX, cy + yOffset, paint)
        }
        
        // Right HUD Lines
        val hudRightStartX = cx + radius * 1.5f
        for(i in 0..3) {
            val yOffset = (i - 1.5f) * 20f
            val lineWidth = 30f + cos(animationPhase * 2.5f - i).toFloat() * 15f
            canvas.drawLine(hudRightStartX, cy + yOffset, hudRightStartX + lineWidth, cy + yOffset, paint)
        }
    }

    // ==========================================
    // STATE 3: SPEAKING (Siri-like Filled Wave)
    // ==========================================
    private fun drawSpeaking(canvas: Canvas, cx: Float, cy: Float, alpha: Int) {
        val waveWidth = width * 0.9f
        val startX = cx - waveWidth / 2f
        
        paint.style = Paint.Style.FILL
        // Blend mode screen for overlap highlights
        paint.blendMode = BlendMode.SCREEN

        val colors = intArrayOf(
            Color.argb((alpha * 0.8f).toInt(), Color.red(colorCyan), Color.green(colorCyan), Color.blue(colorCyan)),
            Color.argb((alpha * 0.8f).toInt(), Color.red(colorGreen), Color.green(colorGreen), Color.blue(colorGreen)),
            Color.argb((alpha * 0.8f).toInt(), Color.red(colorYellow), Color.green(colorYellow), Color.blue(colorYellow)),
            Color.argb((alpha * 0.8f).toInt(), Color.red(colorRed), Color.green(colorRed), Color.blue(colorRed))
        )

        for (i in 0 until 4) {
            wavePath.reset()
            paint.color = colors[i]
            
            val phaseOffset = i * 2.3f
            val speedMult = 1f + (i * 0.15f)
            val freqMult = 1f + (i * 0.2f)
            
            val maxAmp = height * 0.45f
            
            val steps = 80
            // Draw top half
            wavePath.moveTo(startX, cy)
            for (step in 0..steps) {
                val progress = step.toFloat() / steps
                val x = startX + progress * waveWidth
                
                // Taper edges
                val envelope = Math.pow(sin(progress * Math.PI).toDouble(), 1.5).toFloat()
                
                val wave = sin(progress * Math.PI * 3f * freqMult + animationPhase * 3f * speedMult + phaseOffset).toFloat()
                val y = cy - Math.abs(wave * maxAmp * envelope) - 5f // absolute value for clear filled ribbon
                
                wavePath.lineTo(x, y)
            }
            
            // Draw bottom half (mirrored)
            for (step in steps downTo 0) {
                val progress = step.toFloat() / steps
                val x = startX + progress * waveWidth
                
                val envelope = Math.pow(sin(progress * Math.PI).toDouble(), 1.5).toFloat()
                val wave = sin(progress * Math.PI * 3f * freqMult + animationPhase * 3f * speedMult + phaseOffset).toFloat()
                val y = cy + Math.abs(wave * maxAmp * envelope) + 5f
                
                wavePath.lineTo(x, y)
            }
            
            wavePath.close()
            canvas.drawPath(wavePath, paint)
        }
        
        paint.blendMode = null // reset
    }

    private fun blendColors(color1: Int, color2: Int, ratio: Float): Int {
        val inverseRatio = 1f - ratio
        val r = (Color.red(color1) * inverseRatio + Color.red(color2) * ratio).toInt()
        val g = (Color.green(color1) * inverseRatio + Color.green(color2) * ratio).toInt()
        val b = (Color.blue(color1) * inverseRatio + Color.blue(color2) * ratio).toInt()
        return Color.rgb(r, g, b)
    }
}
