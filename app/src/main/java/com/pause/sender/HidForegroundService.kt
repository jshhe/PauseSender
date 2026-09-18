package com.pause.sender

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.content.edit

@SuppressLint("MissingPermission")
class HidForegroundService : Service(), HidKeyboardController.Listener {
    private lateinit var hidController: HidKeyboardController
    private lateinit var overlayController: OverlayController
    private lateinit var notificationManager: NotificationManager
    private val preferences by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private var foregroundStarted = false
    private var bondReceiverRegistered = false
    private var shuttingDown = false
    private var autoConnectNewBondUntil = 0L

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (shuttingDown || intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device = intent.bluetoothDeviceExtra() ?: return
            val bondState = intent.getIntExtra(
                BluetoothDevice.EXTRA_BOND_STATE,
                BluetoothDevice.ERROR,
            )
            val address = safeAddress(device)
            if (bondState == BluetoothDevice.BOND_NONE) {
                clearLastDeviceIfMatches(address)
                hidController.forgetDevice(address)
                return
            }
            if (bondState != BluetoothDevice.BOND_BONDED ||
                SystemClock.elapsedRealtime() > autoConnectNewBondUntil ||
                !isActuallyBonded(device)
            ) {
                return
            }

            autoConnectNewBondUntil = 0L
            updateState(
                state().copy(
                    message = messageForDevice(
                        deviceLabel(device),
                        R.string.state_paired_connecting,
                        R.string.state_paired_connecting_unknown,
                    ),
                    isError = false,
                )
            )
            hidController.connect(device)
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()

        hidController = HidKeyboardController(this, this)
        overlayController = OverlayController(
            context = this,
            onKey = { usage -> hidController.sendKey(usage) },
            onVisibilityChanged = { visible ->
                updateState(state().copy(overlayVisible = visible))
            },
            onError = { message -> reportError(message) },
        )
        registerBondReceiver()
        updateState(
            HidStateSnapshot(
                serviceRunning = true,
                message = UiMessage(R.string.state_service_starting),
            )
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_HID
        if (action == ACTION_STOP) {
            stopSession()
            return START_NOT_STICKY
        }

        if (!startAsForeground()) return START_NOT_STICKY

        when (action) {
            ACTION_START_HID -> {
                val expectingNewBond =
                    intent?.getBooleanExtra(EXTRA_EXPECT_NEW_BOND, false) == true
                if (expectingNewBond) {
                    autoConnectNewBondUntil =
                        SystemClock.elapsedRealtime() + AUTO_CONNECT_BOND_WINDOW_MS
                } else {
                    reconnectLastBondedDevice()
                }
                hidController.start()
            }

            ACTION_CONNECT_DEVICE -> {
                autoConnectNewBondUntil = 0L
                val address = intent?.getStringExtra(EXTRA_DEVICE_ADDRESS)
                if (address.isNullOrBlank()) reportError(UiMessage(R.string.error_missing_connect_device))
                else hidController.connect(address)
            }

            ACTION_SHOW_OVERLAY -> {
                hidController.start()
                when {
                    !state().connected -> reportError(UiMessage(R.string.error_connect_before_overlay))
                    !Settings.canDrawOverlays(this) ->
                        reportError(UiMessage(R.string.error_overlay_permission_missing))
                    else -> overlayController.show(connected = true)
                }
            }

            ACTION_HIDE_OVERLAY -> hideOverlayAndDisconnect()

            ACTION_CANCEL_NEW_BOND -> autoConnectNewBondUntil = 0L

            ACTION_SEND_KEY -> {
                val usage = intent?.getIntExtra(EXTRA_KEY_USAGE, 0) ?: 0
                if (usage in ALLOWED_KEYS) hidController.sendKey(usage)
                else reportError(UiMessage(R.string.error_unconfigured_key))
            }

            ACTION_QUERY_STATE -> broadcastState()

            ACTION_REFRESH_LOCALE -> refreshLocalizedSurfaces()
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground(): Boolean {
        if (foregroundStarted) return true
        return try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundStarted = true
            true
        } catch (error: SecurityException) {
            reportError(UiMessage(R.string.error_foreground_service_permission))
            stopSelf()
            false
        }
    }

    override fun onProfileReady() {
        if (shuttingDown) return
        updateState(
            state().copy(
                profileReady = true,
                message = UiMessage(R.string.state_profile_ready),
                isError = false,
            )
        )
    }

    override fun onProfileUnavailable() {
        if (shuttingDown) return
        updateState(
            state().copy(
                profileReady = false,
                registered = false,
                connected = false,
                connecting = false,
            )
        )
    }

    override fun onRegistrationChanged(registered: Boolean) {
        if (shuttingDown) return
        updateState(
            state().copy(
                registered = registered,
                connected = if (registered) state().connected else false,
                connecting = false,
                message = if (registered) {
                    UiMessage(R.string.state_keyboard_registered)
                } else {
                    UiMessage(R.string.state_keyboard_unregistered)
                },
                isError = false,
            )
        )
    }

    override fun onConnectionStateChanged(device: BluetoothDevice, connectionState: Int) {
        if (shuttingDown) return
        val name = deviceLabel(device)
        when (connectionState) {
            BluetoothProfile.STATE_CONNECTING -> updateState(
                state().copy(
                    connecting = true,
                    connected = false,
                    deviceName = name,
                    message = messageForDevice(
                        name,
                        R.string.state_connecting_device,
                        R.string.state_connecting_device_unknown,
                    ),
                    isError = false,
                )
            )

            BluetoothProfile.STATE_CONNECTED -> {
                safeAddress(device).takeIf { it.isNotBlank() }?.let { address ->
                    preferences.edit { putString(PREF_LAST_DEVICE, address) }
                }
                overlayController.setConnected(true)
                updateState(
                    state().copy(
                        connecting = false,
                        connected = true,
                        deviceName = name,
                        message = messageForDevice(
                            name,
                            R.string.state_connected_device,
                            R.string.state_connected_device_unknown,
                        ),
                        isError = false,
                    )
                )
            }

            BluetoothProfile.STATE_DISCONNECTING -> updateState(
                state().copy(
                    connecting = true,
                    connected = false,
                    deviceName = name,
                    message = messageForDevice(
                        name,
                        R.string.state_disconnecting_device,
                        R.string.state_disconnecting_device_unknown,
                    ),
                    isError = false,
                )
            )

            BluetoothProfile.STATE_DISCONNECTED -> {
                overlayController.setConnected(false)
                val message = if (state().overlayVisible) {
                    messageForDevice(
                        name,
                        R.string.state_device_disconnected_overlay_paused,
                        R.string.state_device_disconnected_overlay_paused_unknown,
                    )
                } else {
                    messageForDevice(
                        name,
                        R.string.state_device_disconnected,
                        R.string.state_device_disconnected_unknown,
                    )
                }
                updateState(
                    state().copy(
                        connecting = false,
                        connected = false,
                        deviceName = name,
                        message = message,
                        isError = false,
                    )
                )
            }
        }
    }

    override fun onVirtualCableUnplug(device: BluetoothDevice) {
        if (shuttingDown) return
        clearLastDeviceIfMatches(safeAddress(device))
    }

    override fun onError(message: UiMessage) {
        if (!shuttingDown) reportError(message)
    }

    private fun reportError(message: UiMessage) {
        if (shuttingDown) return
        updateState(state().copy(message = message, isError = true))
    }

    private fun updateState(newState: HidStateSnapshot) {
        if (shuttingDown) return
        currentState = newState.copy(serviceRunning = true)
        overlayControllerOrNull()?.setConnected(currentState.connected)
        broadcastState()
        refreshNotification()
    }

    private fun state(): HidStateSnapshot = currentState

    private fun broadcastState() {
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    private fun refreshNotification() {
        if (!foregroundStarted) return
        try {
            notificationManager.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: SecurityException) {
            // The service can still run when Android 13+ notification permission is denied.
        }
    }

    private fun createNotificationChannel() {
        val strings = forAppLocale()
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            strings.getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = strings.getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val strings = forAppLocale()
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val hideIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, HidForegroundService::class.java).setAction(ACTION_HIDE_OVERLAY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, HidForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_keyboard)
            .setContentTitle(strings.getString(R.string.notification_title))
            .setContentText(state().message.resolve(strings))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(
                Notification.Action.Builder(
                    null,
                    strings.getString(R.string.notification_hide),
                    hideIntent,
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    null,
                    strings.getString(R.string.notification_stop),
                    stopIntent,
                ).build()
            )
            .build()
    }

    private fun refreshLocalizedSurfaces() {
        createNotificationChannel()
        overlayControllerOrNull()?.refreshLocalizedContent()
        refreshNotification()
        broadcastState()
    }

    private fun hideOverlayAndDisconnect() {
        autoConnectNewBondUntil = 0L
        val wasConnected = state().connected || state().connecting
        val name = state().deviceName
        overlayController.hide()
        if (hidController.disconnect()) {
            updateState(
                state().copy(
                    connected = false,
                    connecting = true,
                    message = if (name.isNullOrBlank()) {
                        UiMessage(R.string.state_overlay_hidden_disconnecting)
                    } else {
                        UiMessage.of(R.string.state_overlay_hidden_disconnecting_device, name)
                    },
                    isError = false,
                )
            )
        } else if (wasConnected) {
            reportError(UiMessage(R.string.error_overlay_disconnect_failed))
        }
    }

    private fun stopSession() {
        if (shuttingDown) return
        shuttingDown = true
        unregisterBondReceiver()
        overlayController.hide()
        hidController.stop()
        running = false
        currentState = HidStateSnapshot(message = UiMessage(R.string.state_service_stopped))
        broadcastState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
    }

    override fun onDestroy() {
        if (!shuttingDown) {
            shuttingDown = true
            overlayController.hide()
            hidController.stop()
        }
        unregisterBondReceiver()
        running = false
        currentState = HidStateSnapshot(message = UiMessage(R.string.state_service_stopped))
        broadcastState()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlayController.reclampPosition()
        refreshLocalizedSurfaces()
    }

    private fun registerBondReceiver() {
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bondReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(bondReceiver, filter)
        }
        bondReceiverRegistered = true
    }

    private fun unregisterBondReceiver() {
        if (!bondReceiverRegistered) return
        unregisterReceiver(bondReceiver)
        bondReceiverRegistered = false
    }

    private fun reconnectLastBondedDevice() {
        if (!hasConnectPermission()) return
        val address = preferences.getString(PREF_LAST_DEVICE, null) ?: return
        val device = findBondedDevice(address)
        if (device == null) {
            preferences.edit { remove(PREF_LAST_DEVICE) }
            return
        }
        hidController.connect(device)
    }

    private fun findBondedDevice(address: String): BluetoothDevice? {
        if (!hasConnectPermission()) return null
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        return try {
            adapter.bondedDevices.firstOrNull { safeAddress(it) == address }
        } catch (_: SecurityException) {
            null
        }
    }

    private fun clearLastDeviceIfMatches(address: String) {
        if (preferences.getString(PREF_LAST_DEVICE, null) == address) {
            preferences.edit { remove(PREF_LAST_DEVICE) }
        }
    }

    private fun isActuallyBonded(device: BluetoothDevice): Boolean {
        if (!hasConnectPermission()) return false
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return false
        return try {
            adapter.bondedDevices.any { safeAddress(it) == safeAddress(device) }
        } catch (_: SecurityException) {
            false
        }
    }

    private fun deviceLabel(device: BluetoothDevice): String? = try {
        device.name?.takeIf { it.isNotBlank() } ?: safeAddress(device).takeIf { it.isNotBlank() }
    } catch (_: SecurityException) {
        safeAddress(device).takeIf { it.isNotBlank() }
    }

    private fun messageForDevice(
        deviceName: String?,
        @StringRes namedMessage: Int,
        @StringRes unnamedMessage: Int,
    ): UiMessage = if (deviceName.isNullOrBlank()) {
        UiMessage(unnamedMessage)
    } else {
        UiMessage.of(namedMessage, deviceName)
    }

    private fun safeAddress(device: BluetoothDevice): String = try {
        device.address
    } catch (_: SecurityException) {
        ""
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun overlayControllerOrNull(): OverlayController? =
        if (::overlayController.isInitialized) overlayController else null

    @Suppress("DEPRECATION")
    private fun Intent.bluetoothDeviceExtra(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START_HID = "com.pause.sender.action.START_HID"
        const val ACTION_CONNECT_DEVICE = "com.pause.sender.action.CONNECT_DEVICE"
        const val ACTION_SHOW_OVERLAY = "com.pause.sender.action.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "com.pause.sender.action.HIDE_OVERLAY"
        const val ACTION_CANCEL_NEW_BOND = "com.pause.sender.action.CANCEL_NEW_BOND"
        const val ACTION_SEND_KEY = "com.pause.sender.action.SEND_KEY"
        const val ACTION_QUERY_STATE = "com.pause.sender.action.QUERY_STATE"
        const val ACTION_REFRESH_LOCALE = "com.pause.sender.action.REFRESH_LOCALE"
        const val ACTION_STOP = "com.pause.sender.action.STOP"
        const val ACTION_STATE_CHANGED = "com.pause.sender.action.STATE_CHANGED"

        const val EXTRA_DEVICE_ADDRESS = "device_address"
        const val EXTRA_KEY_USAGE = "key_usage"
        const val EXTRA_EXPECT_NEW_BOND = "expect_new_bond"

        private const val PREFS_NAME = "pause_sender"
        private const val PREF_LAST_DEVICE = "last_device_address"
        private const val NOTIFICATION_CHANNEL_ID = "hid_connection"
        private const val NOTIFICATION_ID = 1001
        private const val AUTO_CONNECT_BOND_WINDOW_MS = 330_000L
        private val ALLOWED_KEYS = setOf(
            HidReports.KEY_SPACE,
            HidReports.KEY_RIGHT_ARROW,
            HidReports.KEY_LEFT_ARROW,
        )

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        private var currentState: HidStateSnapshot = HidStateSnapshot()

        fun snapshot(): HidStateSnapshot = currentState
    }
}
