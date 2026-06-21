package com.example.yoloface

class FpsCounter(private val smoothingFactor: Float = 0.15f) {
    private var previousTimestampNs = 0L
    private var smoothedFps = 0f

    fun recordFrame(timestampNs: Long): Float? {
        if (previousTimestampNs == 0L) {
            previousTimestampNs = timestampNs
            return null
        }
        val elapsedNs = timestampNs - previousTimestampNs
        previousTimestampNs = timestampNs
        if (elapsedNs <= 0L) return null
        val instantaneousFps = 1_000_000_000f / elapsedNs
        smoothedFps = if (smoothedFps == 0f) instantaneousFps else {
            smoothedFps + smoothingFactor * (instantaneousFps - smoothedFps)
        }
        return smoothedFps
    }
}
