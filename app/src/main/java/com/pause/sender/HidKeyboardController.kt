package com.pause.sender

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock

@SuppressLint("MissingPermission")
class HidKeyboardController(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onProfileReady()
        fun onProfileUnavailable()
        fun onRegistrationChanged(registered: Boolean)
        fun onConnectionStateChanged(device: BluetoothDevice, state: Int)
        fun onVirtualCableUnplug(device: BluetoothDevice)
        fun onError(message: UiMessage)
    }

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter

    private val reportThread = HandlerThread("PauseSenderHidReports").apply { start() }
    private val reportHandler = Handler(reportThread.looper)

    @Volatile
    private var hidDevice: BluetoothHidDevice? = null

    @Volatile
    private var connectedDevice: BluetoothDevice? = null

    @Volatile
    private var registered = false

    @Volatile
    private var profileRequested = false

    @Volatile
    private var registrationRequested = false

    @Volatile
    private var pendingDevice: BluetoothDevice? = null

    @Volatile
    private var connectionTarget: BluetoothDevice? = null

    @Volatile
    private var disconnectRequested = false

    @Volatile
    private var stopped = false

    @Volatile
    private var currentInputReport = HidReports.keyUp()

    @Volatile
    private var currentOutputReport = HidReports.outputReport()

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE || proxy !is BluetoothHidDevice) return
            if (stopped) {
                closeProfileProxy(proxy)
                return
            }
            hidDevice = proxy
            profileRequested = false
            registrationRequested = false
            listener.onProfileReady()
            registerApp()
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hidDevice = null
            registered = false
            connectedDevice = null
            connectionTarget = null
            disconnectRequested = false
            profileRequested = false
            registrationRequested = false
            currentInputReport = HidReports.keyUp()
            if (stopped) return
            listener.onProfileUnavailable()
            listener.onRegistrationChanged(false)
            listener.onError(UiMessage(R.string.error_hid_service_disconnected))
        }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) {
            if (stopped) {
                if (isRegistered) unregisterAppSafely(hidDevice)
                return
            }
            registered = isRegistered
            registrationRequested = false
            if (isRegistered) {
                val target = pendingDevice ?: pluggedDevice
                pendingDevice = null
                listener.onRegistrationChanged(true)
                if (target != null) connect(target)
            } else {
                connectedDevice = null
                connectionTarget = null
                disconnectRequested = false
                currentInputReport = HidReports.keyUp()
                listener.onRegistrationChanged(false)
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            if (stopped) return
            val address = safeAddress(device)
            val connectedAddress = connectedDevice?.let(::safeAddress)
            val targetAddress = connectionTarget?.let(::safeAddress)

            when (state) {
                BluetoothProfile.STATE_CONNECTING -> {
                    if (targetAddress != null && targetAddress != address) return
                    connectionTarget = device
                }

                BluetoothProfile.STATE_CONNECTED -> {
                    if (targetAddress != null && targetAddress != address) {
                        disconnectDeviceSafely(hidDevice, device)
                        return
                    }
                    connectedDevice = device
                    connectionTarget = device
                    if (disconnectRequested) {
                        // A connection may complete while a hide/stop disconnect is in flight.
                        // Keep the disconnect intent authoritative and do not expose a transient
                        // connected state back to the service.
                        disconnectDeviceSafely(hidDevice, device)
                        return
                    }
                    disconnectRequested = false
                }

                BluetoothProfile.STATE_DISCONNECTING -> {
                    if (connectedAddress != address && targetAddress != address) return
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (connectedAddress != address && targetAddress != address) return
                    if (connectedAddress == address) connectedDevice = null
                    if (targetAddress == address) connectionTarget = null
                    disconnectRequested = false
                    currentInputReport = HidReports.keyUp()
                }
            }
            listener.onConnectionStateChanged(device, state)

            if (state == BluetoothProfile.STATE_DISCONNECTED) {
                val nextTarget = pendingDevice
                if (nextTarget != null && registered && !stopped) {
                    pendingDevice = null
                    connect(nextTarget)
                }
            }
        }

        override fun onGetReport(
            device: BluetoothDevice,
            type: Byte,
            id: Byte,
            bufferSize: Int,
        ) {
            if (stopped) return
            val hid = hidDevice ?: return
            if (id.toInt() != HidReports.REPORT_ID) {
                reportErrorSafely(hid, device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
                return
            }
            val report = when (type) {
                BluetoothHidDevice.REPORT_TYPE_INPUT -> currentInputReport
                BluetoothHidDevice.REPORT_TYPE_OUTPUT -> currentOutputReport
                else -> {
                    reportErrorSafely(hid, device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
                    return
                }
            }
            val response = HidReports.fitToRequestedSize(report, bufferSize)
            try {
                hid.replyReport(device, type, id, response)
            } catch (_: SecurityException) {
                postError(UiMessage(R.string.error_hid_reply_report_permission))
            }
        }

        override fun onSetReport(
            device: BluetoothDevice,
            type: Byte,
            id: Byte,
            data: ByteArray,
        ) {
            if (stopped) return
            val hid = hidDevice ?: return
            when {
                id.toInt() != HidReports.REPORT_ID ->
                    reportErrorSafely(hid, device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)

                type != BluetoothHidDevice.REPORT_TYPE_OUTPUT || data.isEmpty() ->
                    reportErrorSafely(hid, device, BluetoothHidDevice.ERROR_RSP_INVALID_PARAM)

                else -> currentOutputReport = data.copyOf(HidReports.OUTPUT_REPORT_SIZE)
            }
        }

        override fun onInterruptData(
            device: BluetoothDevice,
            reportId: Byte,
            data: ByteArray,
        ) {
            if (!stopped && reportId.toInt() == HidReports.REPORT_ID && data.isNotEmpty()) {
                currentOutputReport = data.copyOf(HidReports.OUTPUT_REPORT_SIZE)
            }
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice) {
            if (stopped) return
            val address = safeAddress(device)
            val connectedAddress = connectedDevice?.let(::safeAddress)
            val targetAddress = connectionTarget?.let(::safeAddress)
            val wasCurrent = connectedAddress == address || targetAddress == address
            if (pendingDevice?.let(::safeAddress) == address) pendingDevice = null
            if (wasCurrent) {
                connectedDevice = null
                connectionTarget = null
                disconnectRequested = false
                currentInputReport = HidReports.keyUp()
            }
            listener.onVirtualCableUnplug(device)
            if (wasCurrent) {
                listener.onConnectionStateChanged(device, BluetoothProfile.STATE_DISCONNECTED)
            }
        }
    }

    fun start() {
        if (stopped) return
        if (hidDevice != null) {
            registerApp()
            return
        }
        if (profileRequested) return

        val bluetoothAdapter = adapter
        if (bluetoothAdapter == null) {
            listener.onError(UiMessage(R.string.unsupported_bluetooth))
            return
        }

        profileRequested = true
        val accepted = try {
            bluetoothAdapter.getProfileProxy(
                appContext,
                profileListener,
                BluetoothProfile.HID_DEVICE,
            )
        } catch (_: SecurityException) {
            profileRequested = false
            listener.onError(UiMessage(R.string.error_hid_profile_permission))
            return
        }
        if (!accepted) {
            profileRequested = false
            listener.onError(UiMessage(R.string.error_hid_profile_unavailable))
        }
    }

    private fun registerApp() {
        if (stopped || registered || registrationRequested) return
        val hid = hidDevice ?: return

        val settings = BluetoothHidDeviceAppSdpSettings(
            appContext.getString(R.string.hid_sdp_name),
            appContext.getString(R.string.hid_sdp_description),
            appContext.getString(R.string.hid_sdp_provider),
            BluetoothHidDevice.SUBCLASS1_KEYBOARD,
            KEYBOARD_DESCRIPTOR,
        )
        registrationRequested = true
        val accepted = try {
            hid.registerApp(
                settings,
                null,
                null,
                appContext.mainExecutor,
                callback,
            )
        } catch (_: SecurityException) {
            registrationRequested = false
            listener.onError(UiMessage(R.string.error_hid_register_permission))
            return
        }
        if (!accepted) {
            registrationRequested = false
            listener.onError(UiMessage(R.string.error_hid_register_rejected))
        }
    }

    fun connect(address: String) {
        if (stopped) return
        val bluetoothAdapter = adapter
        if (bluetoothAdapter == null) {
            listener.onError(UiMessage(R.string.error_bluetooth_adapter_unavailable))
            return
        }
        val device = try {
            bluetoothAdapter.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            listener.onError(UiMessage(R.string.error_invalid_device_address))
            return
        } catch (_: SecurityException) {
            listener.onError(UiMessage(R.string.error_bluetooth_device_permission))
            return
        }
        connect(device)
    }

    fun connect(device: BluetoothDevice) {
        if (stopped) return
        val address = safeAddress(device)
        val connected = connectedDevice
        val currentTarget = connectionTarget
        val connectedAddress = connected?.let(::safeAddress)
        val targetAddress = currentTarget?.let(::safeAddress)

        if (connectedAddress == address && !disconnectRequested) return
        if (targetAddress == address && !disconnectRequested) return
        if (!registered) {
            pendingDevice = device
            start()
            return
        }

        val deviceToDisconnect = connected ?: currentTarget
        if (deviceToDisconnect != null &&
            safeAddress(deviceToDisconnect) != address
        ) {
            pendingDevice = device
            requestDisconnect(deviceToDisconnect, clearPending = false)
            return
        }
        if (disconnectRequested) {
            pendingDevice = device
            return
        }

        pendingDevice = null
        connectionTarget = device
        val accepted = connectDeviceSafely(hidDevice, device)
        if (!accepted) {
            connectionTarget = null
            listener.onError(UiMessage(R.string.error_hid_connect_failed))
        }
    }

    fun sendKey(usage: Int): Boolean {
        if (stopped || disconnectRequested) return false
        val hid = hidDevice
        val device = connectedDevice
        if (!registered || hid == null || device == null) {
            listener.onError(UiMessage(R.string.error_keyboard_not_connected))
            return false
        }

        val keyDown = try {
            HidReports.keyDown(usage)
        } catch (_: IllegalArgumentException) {
            listener.onError(UiMessage(R.string.error_key_outside_descriptor))
            return false
        }
        val posted = reportHandler.post {
            if (stopped || disconnectRequested || hidDevice !== hid ||
                connectedDevice?.let(::safeAddress) != safeAddress(device)
            ) {
                return@post
            }
            currentInputReport = keyDown
            val pressed = sendReportSafely(hid, device, keyDown)
            SystemClock.sleep(KEY_PRESS_DURATION_MS)
            val keyUp = HidReports.keyUp()
            currentInputReport = keyUp
            val released = sendReportSafely(hid, device, keyUp)
            if (!pressed || !released) {
                postError(UiMessage(R.string.error_key_report_failed))
            }
        }
        if (!posted) listener.onError(UiMessage(R.string.error_key_thread_stopped))
        return posted
    }

    fun disconnect(): Boolean {
        pendingDevice = null
        val device = connectedDevice ?: connectionTarget ?: return false
        return requestDisconnect(device, clearPending = true)
    }

    fun forgetDevice(address: String) {
        if (pendingDevice?.let(::safeAddress) == address) pendingDevice = null
        val current = connectedDevice ?: connectionTarget
        if (current?.let(::safeAddress) == address) disconnect()
    }

    private fun requestDisconnect(device: BluetoothDevice, clearPending: Boolean): Boolean {
        if (stopped) return false
        if (clearPending) pendingDevice = null
        if (disconnectRequested) return true
        val hid = hidDevice ?: return false
        disconnectRequested = true
        reportHandler.removeCallbacksAndMessages(null)
        val posted = reportHandler.postAtFrontOfQueue {
            val keyUp = HidReports.keyUp()
            currentInputReport = keyUp
            sendReportSafely(hid, device, keyUp)
            val accepted = disconnectDeviceSafely(hid, device)
            if (!accepted) {
                disconnectRequested = false
                postError(UiMessage(R.string.error_hid_disconnect_failed))
            }
        }
        if (!posted) {
            disconnectRequested = false
            listener.onError(UiMessage(R.string.error_hid_disconnect_schedule_failed))
        }
        return posted
    }

    fun stop() {
        if (stopped) return
        stopped = true
        disconnectRequested = true
        pendingDevice = null
        reportHandler.removeCallbacksAndMessages(null)
        val posted = reportHandler.postAtFrontOfQueue {
            teardown()
            reportThread.quitSafely()
        }
        if (!posted) {
            teardown()
            reportThread.quitSafely()
        }
    }

    private fun teardown() {
        val hid = hidDevice
        val device = connectedDevice ?: connectionTarget
        if (hid != null && device != null) {
            val keyUp = HidReports.keyUp()
            currentInputReport = keyUp
            sendReportSafely(hid, device, keyUp)
            disconnectDeviceSafely(hid, device)
        }
        unregisterAppSafely(hid)
        if (hid != null) closeProfileProxy(hid)
        hidDevice = null
        connectedDevice = null
        connectionTarget = null
        registered = false
        profileRequested = false
        registrationRequested = false
        pendingDevice = null
        currentInputReport = HidReports.keyUp()
        currentOutputReport = HidReports.outputReport()
    }

    private fun connectDeviceSafely(hid: BluetoothHidDevice?, device: BluetoothDevice): Boolean =
        try {
            hid?.connect(device) == true
        } catch (_: SecurityException) {
            false
        }

    private fun disconnectDeviceSafely(
        hid: BluetoothHidDevice?,
        device: BluetoothDevice,
    ): Boolean = try {
        hid?.disconnect(device) == true
    } catch (_: SecurityException) {
        false
    }

    private fun sendReportSafely(
        hid: BluetoothHidDevice,
        device: BluetoothDevice,
        report: ByteArray,
    ): Boolean = try {
        hid.sendReport(device, HidReports.REPORT_ID, report)
    } catch (_: SecurityException) {
        false
    }

    private fun unregisterAppSafely(hid: BluetoothHidDevice?) {
        try {
            hid?.unregisterApp()
        } catch (_: SecurityException) {
            // The service is already shutting down; there is no UI to update.
        }
    }

    private fun closeProfileProxy(hid: BluetoothHidDevice) {
        try {
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
        } catch (_: SecurityException) {
            // The proxy is no longer usable after permission revocation anyway.
        }
    }

    private fun reportErrorSafely(
        hid: BluetoothHidDevice,
        device: BluetoothDevice,
        error: Byte,
    ) {
        try {
            hid.reportError(device, error)
        } catch (_: SecurityException) {
            postError(UiMessage(R.string.error_hid_reply_request_permission))
        }
    }

    private fun postError(message: UiMessage) {
        if (stopped || disconnectRequested) return
        appContext.mainExecutor.execute {
            if (!stopped && !disconnectRequested) listener.onError(message)
        }
    }

    private fun safeAddress(device: BluetoothDevice): String = try {
        device.address
    } catch (_: SecurityException) {
        ""
    }

    companion object {
        private const val KEY_PRESS_DURATION_MS = 18L

        /** Standard boot-keyboard descriptor with LED output support and no report ID. */
        val KEYBOARD_DESCRIPTOR: ByteArray = byteArrayOf(
            0x05, 0x01,       // Usage Page (Generic Desktop)
            0x09, 0x06,       // Usage (Keyboard)
            0xA1.toByte(), 0x01, // Collection (Application)
            0x05, 0x07,       // Usage Page (Keyboard)
            0x19, 0xE0.toByte(), // Usage Minimum (Left Control)
            0x29, 0xE7.toByte(), // Usage Maximum (Right GUI)
            0x15, 0x00,
            0x25, 0x01,
            0x75, 0x01,
            0x95.toByte(), 0x08,
            0x81.toByte(), 0x02, // Input (modifier byte)
            0x95.toByte(), 0x01,
            0x75, 0x08,
            0x81.toByte(), 0x01, // Input (reserved byte)
            0x95.toByte(), 0x05,
            0x75, 0x01,
            0x05, 0x08,       // Usage Page (LEDs)
            0x19, 0x01,
            0x29, 0x05,
            0x91.toByte(), 0x02, // Output (LEDs)
            0x95.toByte(), 0x01,
            0x75, 0x03,
            0x91.toByte(), 0x01, // Output (padding)
            0x95.toByte(), 0x06,
            0x75, 0x08,
            0x15, 0x00,
            0x25, 0x65,
            0x05, 0x07,
            0x19, 0x00,
            0x29, 0x65,
            0x81.toByte(), 0x00, // Input (six key slots)
            0xC0.toByte(),
        )
    }
}
