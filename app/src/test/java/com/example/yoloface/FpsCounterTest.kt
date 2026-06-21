package com.example.yoloface

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FpsCounterTest {
    @Test
    fun `first frame does not produce fps`() {
        assertNull(FpsCounter().recordFrame(1_000_000_000L))
    }

    @Test
    fun `reports fps from frame interval`() {
        val counter = FpsCounter()
        counter.recordFrame(1_000_000_000L)

        assertEquals(10f, counter.recordFrame(1_100_000_000L)!!, 0.001f)
    }
}
