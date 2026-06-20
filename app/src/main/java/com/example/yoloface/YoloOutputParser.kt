package com.example.yoloface

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

object YoloOutputParser {
    fun parse(
        raw: Array<FloatArray>,
        attributeFirst: Boolean,
        labels: List<String>,
        confidenceThreshold: Float,
        iouThreshold: Float,
        inputWidth: Int,
        inputHeight: Int,
        imageWidth: Float,
        imageHeight: Float,
        stage: DetectionStage = DetectionStage.TEXT,
    ): List<DetectionBox> {
        if (raw.isEmpty() || labels.isEmpty()) return emptyList()
        val boxCount = if (attributeFirst) raw.first().size else raw.size
        val getValue: (Int, Int) -> Float = if (attributeFirst) {
            { box, attribute -> raw[attribute][box] }
        } else {
            { box, attribute -> raw[box][attribute] }
        }
        val candidates = ArrayList<DetectionBox>()
        for (boxIndex in 0 until boxCount) {
            var classId = 0
            var confidence = Float.NEGATIVE_INFINITY
            for (candidateClass in labels.indices) {
                val score = getValue(boxIndex, candidateClass + 4)
                if (score > confidence) {
                    confidence = score
                    classId = candidateClass
                }
            }
            if (!confidence.isFinite() || confidence < confidenceThreshold) continue

            val centerX = getValue(boxIndex, 0)
            val centerY = getValue(boxIndex, 1)
            val width = getValue(boxIndex, 2)
            val height = getValue(boxIndex, 3)
            val normalized = max(max(centerX, centerY), max(width, height)) <= 2f
            val xScale = if (normalized) imageWidth else imageWidth / inputWidth
            val yScale = if (normalized) imageHeight else imageHeight / inputHeight
            val bounds = RectF(
                ((centerX - width / 2f) * xScale).coerceIn(0f, imageWidth),
                ((centerY - height / 2f) * yScale).coerceIn(0f, imageHeight),
                ((centerX + width / 2f) * xScale).coerceIn(0f, imageWidth),
                ((centerY + height / 2f) * yScale).coerceIn(0f, imageHeight),
            )
            if (bounds.width() > 0f && bounds.height() > 0f) {
                candidates += DetectionBox(bounds, confidence, classId, labels[classId], stage)
            }
        }
        return classAwareNms(candidates, iouThreshold)
    }

    private fun classAwareNms(candidates: List<DetectionBox>, threshold: Float): List<DetectionBox> {
        val selected = ArrayList<DetectionBox>()
        candidates.groupBy { it.classId }.values.forEach { classCandidates ->
            val remaining = classCandidates.sortedByDescending { it.confidence }.toMutableList()
            while (remaining.isNotEmpty()) {
                val best = remaining.removeAt(0)
                selected += best
                remaining.removeAll { intersectionOverUnion(best.bounds, it.bounds) > threshold }
            }
        }
        return selected.sortedByDescending { it.confidence }
    }

    private fun intersectionOverUnion(first: RectF, second: RectF): Float {
        val intersectionWidth = max(0f, min(first.right, second.right) - max(first.left, second.left))
        val intersectionHeight = max(0f, min(first.bottom, second.bottom) - max(first.top, second.top))
        val intersection = intersectionWidth * intersectionHeight
        val union = first.width() * first.height() + second.width() * second.height() - intersection
        return if (union <= 0f) 0f else intersection / union
    }
}
