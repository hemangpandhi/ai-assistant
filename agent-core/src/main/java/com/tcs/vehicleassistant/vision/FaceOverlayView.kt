package com.tcs.vehicleassistant.vision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class FaceOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var activeFaces: List<RecognizedFace> = emptyList()
    private var imageWidth = 640f
    private var imageHeight = 480f

    private val boxPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 50f
        isAntiAlias = true
        setShadowLayer(5f, 0f, 0f, Color.BLACK)
    }

    fun setFaces(faces: List<RecognizedFace>, imageWidth: Int, imageHeight: Int) {
        this.activeFaces = faces
        this.imageWidth = imageWidth.toFloat()
        this.imageHeight = imageHeight.toFloat()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (activeFaces.isEmpty()) return

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        // image is scaled fitCenter inside the view
        val scale = Math.min(viewWidth / imageWidth, viewHeight / imageHeight)
        val scaledWidth = imageWidth * scale
        val scaledHeight = imageHeight * scale

        // calculate offsets for fitCenter
        val offsetX = (viewWidth - scaledWidth) / 2f
        val offsetY = (viewHeight - scaledHeight) / 2f

        for (face in activeFaces) {
            val rect = face.boundingBox

            // map coordinates to the view
            val left = offsetX + rect.left * scale
            val top = offsetY + rect.top * scale
            val right = offsetX + rect.right * scale
            val bottom = offsetY + rect.bottom * scale

            // The image is mirrored (scaleX="-1" in XML), so we must mirror the X coordinates
            // left becomes (viewWidth - right) and right becomes (viewWidth - left)
            val mirroredLeft = viewWidth - right
            val mirroredRight = viewWidth - left

            canvas.drawRect(mirroredLeft, top, mirroredRight, bottom, boxPaint)
            
            // Draw name slightly above the bounding box
            val textWidth = textPaint.measureText(face.name)
            val textX = mirroredLeft + (mirroredRight - mirroredLeft) / 2f - textWidth / 2f
            canvas.drawText(face.name, textX, top - 20f, textPaint)
        }
    }
}
