package com.example.yoloface

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.absoluteValue
import kotlin.math.max

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    init {
        setWillNotDraw(false)
    }

    private val paint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    
    private val objectTypeTextPaint = Paint().apply {
        textSize = 30f
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 10f
    }
    private val cropGuidePaint = Paint().apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private var boundingBoxes: List<DetectionBox> = emptyList()
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1
    private var cropRegion: RectF? = null

    fun setResults(boxes: List<DetectionBox>, imgWidth: Int, imgHeight: Int, cropRegion: RectF? = null) {
        this.boundingBoxes = boxes
        this.imageWidth = imgWidth
        this.imageHeight = imgHeight
        this.cropRegion = cropRegion
        invalidate()
    }

    var mappedBox = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        // Draw a red border along the edges of the view
        canvas.drawRect(0f, 0f, viewWidth, viewHeight, borderPaint)
        
        if (imageWidth == 1 || imageHeight == 1) {
            return
        }

        // PreviewView defaults to FILL_CENTER. We compute the scale factor to fill the view.
        val scaleX = viewWidth / imageWidth.toFloat()
        val scaleY = viewHeight / imageHeight.toFloat()
        val scale = max(scaleX, scaleY)

        val scaledWidth = imageWidth * scale
        val scaledHeight = imageHeight * scale

        // Calculate offset to center the scaled image
        val offsetX = (viewWidth - scaledWidth) / 2f
        val offsetY = (viewHeight - scaledHeight) / 2f

        cropRegion?.let { crop ->
            canvas.drawRect(
                crop.left * scale + offsetX,
                crop.top * scale + offsetY,
                crop.right * scale + offsetX,
                crop.bottom * scale + offsetY,
                cropGuidePaint,
            )
        }

        for (detection in boundingBoxes) {
            val box = detection.bounds
            // Map box coordinates from image space to view space
            mappedBox.left = (box.left * scale) + offsetX
            mappedBox.top = (box.top * scale) + offsetY
            mappedBox.right = (box.right * scale) + offsetX
            mappedBox.bottom = (box.bottom * scale) + offsetY

            val color = colorForDetection(detection)
            paint.color = color
            objectTypeTextPaint.color = color
            canvas.drawRect(mappedBox, paint)
            val prefix = if (detection.stage == DetectionStage.PILL) "Pill" else "Imprint"
            val text = "$prefix: ${detection.label} ${(detection.confidence * 100).toInt()}%"
            canvas.drawText(text, mappedBox.left, (mappedBox.top - 10f).coerceAtLeast(32f), objectTypeTextPaint)
        }
    }

    private fun colorForDetection(detection: DetectionBox): Int {
        if (detection.stage == DetectionStage.PILL) return Color.CYAN
        val classId = detection.classId
        val hue = ((classId * 137).absoluteValue % 360).toFloat()
        return Color.HSVToColor(floatArrayOf(hue, 0.85f, 1f))
    }
}
