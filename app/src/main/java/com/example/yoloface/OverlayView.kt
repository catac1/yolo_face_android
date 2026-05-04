package com.example.yoloface

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import java.util.Locale
import kotlin.math.max

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    init {
        setWillNotDraw(false)
    }

    private val paint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    
    private val objectTypeTextPaint = Paint().apply {
        color = Color.GREEN
        textSize = 30f
        style = Paint.Style.FILL
    }

    private val objectConfidenceTextPaint = Paint().apply {
        color = Color.MAGENTA
        textSize = 30f
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 10f
    }

    private var boundingBoxes: List<FaceBox> = emptyList()
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1

    fun setResults(boxes: List<FaceBox>, imgWidth: Int, imgHeight: Int) {
        this.boundingBoxes = boxes
        this.imageWidth = imgWidth
        this.imageHeight = imgHeight
        invalidate()
    }

    var mappedBox = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        // Draw a red border along the edges of the view
        canvas.drawRect(0f, 0f, viewWidth, viewHeight, borderPaint)
        
        if (boundingBoxes.isEmpty() || imageWidth == 1 || imageHeight == 1) {
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

        for (faceBox in boundingBoxes) {
            val box = faceBox.bounds
            val conf = faceBox.confidence
            // Map box coordinates from image space to view space
            mappedBox.left = (box.left * scale) + offsetX
            mappedBox.top = (box.top * scale) + offsetY
            mappedBox.right = (box.right * scale) + offsetX
            mappedBox.bottom = (box.bottom * scale) + offsetY

            canvas.drawRect(mappedBox, paint)
            
            val confText = String.format(Locale.KOREAN,"%d", (conf * 100).toInt())
            canvas.drawText("Face", mappedBox.left, mappedBox.top - 10f, objectTypeTextPaint)
            canvas.drawText(confText, mappedBox.left, mappedBox.top - 50f, objectConfidenceTextPaint)
        }
    }
}
