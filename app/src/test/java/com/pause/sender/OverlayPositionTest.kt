package com.pause.sender

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayPositionTest {
    @Test
    fun `position is clamped inside safe bounds`() {
        assertEquals(
            OverlayPosition(x = 10, y = 20),
            clampOverlayPosition(
                x = -500,
                y = -400,
                left = 10,
                top = 20,
                right = 1010,
                bottom = 2020,
                overlayWidth = 200,
                overlayHeight = 100,
            ),
        )
        assertEquals(
            OverlayPosition(x = 810, y = 1920),
            clampOverlayPosition(
                x = 5000,
                y = 4000,
                left = 10,
                top = 20,
                right = 1010,
                bottom = 2020,
                overlayWidth = 200,
                overlayHeight = 100,
            ),
        )
    }

    @Test
    fun `oversized overlay anchors to safe origin`() {
        assertEquals(
            OverlayPosition(x = 12, y = 24),
            clampOverlayPosition(
                x = 300,
                y = 400,
                left = 12,
                top = 24,
                right = 100,
                bottom = 120,
                overlayWidth = 200,
                overlayHeight = 200,
            ),
        )
    }
}
