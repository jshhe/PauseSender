package com.pause.sender

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HidReportsTest {
    @Test
    fun `space report uses standard keyboard usage`() {
        val report = HidReports.keyDown(HidReports.KEY_SPACE)

        assertEquals(HidReports.REPORT_SIZE, report.size)
        assertEquals(0x2C.toByte(), report[2])
        assertEquals(1, report.count { it != 0.toByte() })
    }

    @Test
    fun `right arrow report uses standard keyboard usage`() {
        assertEquals(0x4F.toByte(), HidReports.keyDown(HidReports.KEY_RIGHT_ARROW)[2])
    }

    @Test
    fun `left arrow report uses standard keyboard usage`() {
        assertEquals(0x50.toByte(), HidReports.keyDown(HidReports.KEY_LEFT_ARROW)[2])
    }

    @Test
    fun `release report clears every key`() {
        assertArrayEquals(ByteArray(8), HidReports.keyUp())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid usage is rejected`() {
        HidReports.keyDown(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `usage outside descriptor range is rejected`() {
        HidReports.keyDown(0x66)
    }

    @Test
    fun `get report response is padded to requested size`() {
        val current = HidReports.keyDown(HidReports.KEY_SPACE)

        val response = HidReports.fitToRequestedSize(current, 16)

        assertEquals(16, response.size)
        assertEquals(HidReports.KEY_SPACE.toByte(), response[2])
        assertEquals(1, response.count { it != 0.toByte() })
    }

    @Test
    fun `get report response never truncates descriptor report`() {
        assertEquals(
            HidReports.REPORT_SIZE,
            HidReports.fitToRequestedSize(HidReports.keyUp(), 1).size,
        )
    }
}
