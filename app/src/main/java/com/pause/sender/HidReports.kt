package com.pause.sender

/** Pure keyboard-report construction kept separate so it can be unit tested. */
object HidReports {
    const val KEY_SPACE = 0x2C
    const val KEY_RIGHT_ARROW = 0x4F
    const val KEY_LEFT_ARROW = 0x50

    const val REPORT_ID = 0
    const val REPORT_SIZE = 8
    const val OUTPUT_REPORT_SIZE = 1
    private const val MAX_KEY_USAGE = 0x65

    fun keyDown(usage: Int): ByteArray {
        require(usage in 1..MAX_KEY_USAGE) {
            "HID usage must be inside the keyboard descriptor range"
        }
        return ByteArray(REPORT_SIZE).also { report ->
            report[2] = usage.toByte()
        }
    }

    fun keyUp(): ByteArray = ByteArray(REPORT_SIZE)

    fun outputReport(): ByteArray = ByteArray(OUTPUT_REPORT_SIZE)

    fun fitToRequestedSize(report: ByteArray, requestedSize: Int): ByteArray {
        val responseSize = maxOf(report.size, requestedSize.coerceAtLeast(0))
        return report.copyOf(responseSize)
    }
}
