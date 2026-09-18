package com.pause.sender

data class HidStateSnapshot(
    val serviceRunning: Boolean = false,
    val profileReady: Boolean = false,
    val registered: Boolean = false,
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val deviceName: String? = null,
    val overlayVisible: Boolean = false,
    val message: UiMessage = UiMessage(R.string.state_not_started),
    val isError: Boolean = false,
)
