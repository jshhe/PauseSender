package com.pause.sender

internal data class OverlayPosition(
    val x: Int,
    val y: Int,
)

internal fun clampOverlayPosition(
    x: Int,
    y: Int,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    overlayWidth: Int,
    overlayHeight: Int,
): OverlayPosition {
    val maxX = (right - overlayWidth).coerceAtLeast(left)
    val maxY = (bottom - overlayHeight).coerceAtLeast(top)
    return OverlayPosition(
        x = x.coerceIn(left, maxX),
        y = y.coerceIn(top, maxY),
    )
}
