package com.pause.sender

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.qmdeve.liquidglass.widget.LiquidGlassView

@SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag")
class MainActivity : AppCompatActivity() {
    private lateinit var mainContent: LinearLayout
    private lateinit var glassBackdrop: ViewGroup
    private lateinit var statusCard: MaterialCardView
    private lateinit var statusGlass: LiquidGlassView
    private lateinit var statusLabel: TextView
    private lateinit var statusText: TextView
    private lateinit var statusSupportText: TextView
    private lateinit var languageButton: ImageButton
    private lateinit var grantBluetoothButton: MaterialButton
    private lateinit var enableBluetoothButton: MaterialButton
    private lateinit var startPairingButton: MaterialButton
    private lateinit var discoverAgainButton: MaterialButton
    private lateinit var pairedDevicesContainer: LinearLayout
    private lateinit var grantOverlayButton: MaterialButton
    private lateinit var showOverlayButton: MaterialButton
    private lateinit var hideOverlayButton: MaterialButton
    private lateinit var stopServiceButton: MaterialButton

    private val bluetoothAdapter: BluetoothAdapter?
        get() = getSystemService(BluetoothManager::class.java)?.adapter

    private var stateReceiverRegistered = false
    private var bluetoothReceiverRegistered = false
    private var pendingDiscoverable = false
    private var pairingStartRequested = false
    private var pendingShowOverlay = false

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        render()
        loadBondedDevices()
        if (isBluetoothEnabled()) {
            resumePendingDiscoverability()
        } else {
            pendingDiscoverable = false
            pairingStartRequested = false
            toast(R.string.toast_bluetooth_not_enabled)
        }
    }

    private val discoverableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode > 0) {
            toast(
                resources.getQuantityString(
                    R.plurals.discoverable_duration_seconds,
                    result.resultCode,
                    result.resultCode,
                )
            )
        } else {
            if (HidForegroundService.running) {
                sendServiceAction(HidForegroundService.ACTION_CANCEL_NEW_BOND)
            }
            toast(R.string.toast_discoverable_denied)
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != HidForegroundService.ACTION_STATE_CHANGED) return
            render()
            loadBondedDevices()
            resumePendingDiscoverability()
        }
    }

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            render()
            loadBondedDevices()
            if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) ==
                BluetoothAdapter.STATE_ON
            ) {
                resumePendingDiscoverability()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingDiscoverable = savedInstanceState?.getBoolean(STATE_PENDING_DISCOVERABLE) == true
        pendingShowOverlay = savedInstanceState?.getBoolean(STATE_PENDING_SHOW_OVERLAY) == true
        setContentView(R.layout.activity_main)
        bindViews()
        configureInsets()
        configureGlass()
        configureResponsiveWidth()
        wireActions()
        render()
        loadBondedDevices()
    }

    override fun onStart() {
        super.onStart()
        val stateFilter = IntentFilter(HidForegroundService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, stateFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(stateReceiver, stateFilter)
        }
        stateReceiverRegistered = true

        val bluetoothFilter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothStateReceiver, bluetoothFilter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(bluetoothStateReceiver, bluetoothFilter)
        }
        bluetoothReceiverRegistered = true
    }

    override fun onResume() {
        super.onResume()
        render()
        loadBondedDevices()
        if (HidForegroundService.running) {
            sendServiceAction(HidForegroundService.ACTION_REFRESH_LOCALE)
        }
        finishPendingOverlayRequest()
        resumePendingDiscoverability()
    }

    override fun onStop() {
        if (stateReceiverRegistered) {
            unregisterReceiver(stateReceiver)
            stateReceiverRegistered = false
        }
        if (bluetoothReceiverRegistered) {
            unregisterReceiver(bluetoothStateReceiver)
            bluetoothReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_PENDING_DISCOVERABLE, pendingDiscoverable)
        outState.putBoolean(STATE_PENDING_SHOW_OVERLAY, pendingShowOverlay)
        super.onSaveInstanceState(outState)
    }

    private fun bindViews() {
        mainContent = findViewById(R.id.mainContent)
        glassBackdrop = findViewById(R.id.glassBackdrop)
        statusCard = findViewById(R.id.statusCard)
        statusGlass = findViewById(R.id.statusGlass)
        statusLabel = findViewById(R.id.statusLabel)
        statusText = findViewById(R.id.statusText)
        statusSupportText = findViewById(R.id.statusSupportText)
        languageButton = findViewById(R.id.languageButton)
        grantBluetoothButton = findViewById(R.id.grantBluetoothButton)
        enableBluetoothButton = findViewById(R.id.enableBluetoothButton)
        startPairingButton = findViewById(R.id.startPairingButton)
        discoverAgainButton = findViewById(R.id.discoverAgainButton)
        pairedDevicesContainer = findViewById(R.id.pairedDevicesContainer)
        grantOverlayButton = findViewById(R.id.grantOverlayButton)
        showOverlayButton = findViewById(R.id.showOverlayButton)
        hideOverlayButton = findViewById(R.id.hideOverlayButton)
        stopServiceButton = findViewById(R.id.stopServiceButton)
    }

    private fun configureGlass() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            statusGlass.bind(glassBackdrop)
            statusGlass.setCornerRadius(dp(28).toFloat())
            statusGlass.setRefractionHeight(dp(18).toFloat())
            statusGlass.setRefractionOffset(dp(58).toFloat())
            statusGlass.setTintColorRed(0.88f)
            statusGlass.setTintColorGreen(0.93f)
            statusGlass.setTintColorBlue(1f)
            statusGlass.setTintAlpha(0.12f)
            statusGlass.setDispersion(0.09f)
            statusGlass.setBlurRadius(dp(16).toFloat())
            statusGlass.setTouchEffectEnabled(false)
            statusGlass.setElasticEnabled(false)
            statusCard.setCardBackgroundColor(getColor(R.color.glass_card))
        } else {
            statusGlass.visibility = View.GONE
            statusCard.setCardBackgroundColor(getColor(R.color.glass_card_fallback))
        }
    }

    private fun configureInsets() {
        val baseLeft = dp(20)
        val baseTop = dp(22)
        val baseRight = dp(20)
        val baseBottom = dp(40)
        ViewCompat.setOnApplyWindowInsetsListener(mainContent) { view, insets ->
            val safeInsets = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(
                left = baseLeft + safeInsets.left,
                top = baseTop + safeInsets.top,
                right = baseRight + safeInsets.right,
                bottom = baseBottom + safeInsets.bottom,
            )
            insets
        }
        ViewCompat.requestApplyInsets(mainContent)
    }

    private fun configureResponsiveWidth() {
        mainContent.post {
            val parentWidth = (mainContent.parent as? View)?.width ?: return@post
            if (parentWidth > dp(720)) {
                val params = mainContent.layoutParams as? FrameLayout.LayoutParams ?: return@post
                params.width = dp(680)
                params.gravity = Gravity.CENTER_HORIZONTAL
                mainContent.layoutParams = params
            }
        }
    }

    private fun wireActions() {
        languageButton.setOnClickListener { showLanguageDialog() }
        grantBluetoothButton.setOnClickListener { requestNeededPermissions() }
        enableBluetoothButton.setOnClickListener { requestEnableBluetooth() }
        startPairingButton.setOnClickListener { startPairing() }
        discoverAgainButton.setOnClickListener { requestDiscoverable() }
        grantOverlayButton.setOnClickListener { requestOverlayPermission() }
        showOverlayButton.setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                sendServiceAction(HidForegroundService.ACTION_SHOW_OVERLAY)
            } else {
                requestOverlayPermission()
            }
        }
        hideOverlayButton.setOnClickListener {
            sendServiceAction(HidForegroundService.ACTION_HIDE_OVERLAY)
        }
        stopServiceButton.setOnClickListener {
            if (HidForegroundService.running) sendServiceAction(HidForegroundService.ACTION_STOP)
        }
    }

    private fun startPairing() {
        when {
            bluetoothAdapter == null -> toast(getString(R.string.unsupported_bluetooth))
            else -> requestDiscoverable()
        }
    }

    private fun requestNeededPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions += Manifest.permission.BLUETOOTH_CONNECT
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                permissions += Manifest.permission.BLUETOOTH_ADVERTISE
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        if (permissions.isNotEmpty()) {
            requestPermissions(permissions.toTypedArray(), REQUEST_PERMISSIONS)
        } else {
            render()
            resumePendingDiscoverability()
        }
    }

    private fun requestEnableBluetooth() {
        if (!hasBluetoothPermissions()) {
            requestNeededPermissions()
            return
        }
        try {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to open the system Bluetooth screen", error)
            toast(R.string.toast_open_bluetooth_settings_failed)
        }
    }

    private fun requestDiscoverable() {
        pendingDiscoverable = true
        pairingStartRequested = false
        if (!hasBluetoothPermissions()) {
            requestNeededPermissions()
            return
        }
        if (!isBluetoothEnabled()) {
            requestEnableBluetooth()
            return
        }
        resumePendingDiscoverability()
    }

    private fun resumePendingDiscoverability() {
        if (!pendingDiscoverable || !hasBluetoothPermissions() || !isBluetoothEnabled()) return
        if (!pairingStartRequested) {
            pairingStartRequested = true
            sendServiceAction(HidForegroundService.ACTION_START_HID) {
                putExtra(HidForegroundService.EXTRA_EXPECT_NEW_BOND, true)
            }
        }
        if (!HidForegroundService.snapshot().registered) return

        pendingDiscoverable = false
        pairingStartRequested = false
        launchDiscoverableRequest()
    }

    private fun launchDiscoverableRequest() {
        try {
            val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERABLE_DURATION_SECONDS)
            discoverableLauncher.launch(intent)
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to open the Bluetooth discoverability screen", error)
            if (HidForegroundService.running) {
                sendServiceAction(HidForegroundService.ACTION_CANCEL_NEW_BOND)
            }
            toast(R.string.toast_open_discoverable_settings_failed)
        }
    }

    private fun finishPendingOverlayRequest() {
        if (!pendingShowOverlay) return
        pendingShowOverlay = false
        if (Settings.canDrawOverlays(this)) {
            if (HidForegroundService.snapshot().connected) {
                sendServiceAction(HidForegroundService.ACTION_SHOW_OVERLAY)
            } else {
                toast(R.string.toast_overlay_permission_granted_reconnect)
            }
        }
    }

    private fun requestOverlayPermission() {
        if (!HidForegroundService.snapshot().connected) {
            toast(R.string.toast_connect_before_overlay_permission)
            return
        }
        if (Settings.canDrawOverlays(this)) {
            sendServiceAction(HidForegroundService.ACTION_SHOW_OVERLAY)
            return
        }
        pendingShowOverlay = true
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            "package:$packageName".toUri(),
        )
        try {
            startActivity(intent)
        } catch (_: RuntimeException) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (error: RuntimeException) {
                pendingShowOverlay = false
                Log.e(TAG, "Unable to open the display-over-apps settings", error)
                toast(R.string.toast_open_overlay_settings_failed)
            }
        }
    }

    private fun sendServiceAction(action: String, configure: (Intent.() -> Unit)? = null) {
        val intent = Intent(this, HidForegroundService::class.java).setAction(action)
        configure?.invoke(intent)
        if (HidForegroundService.running) startService(intent) else startForegroundService(intent)
    }

    private fun connectDevice(device: BluetoothDevice) {
        if (!hasBluetoothPermissions()) {
            requestNeededPermissions()
            return
        }
        val address = try {
            device.address
        } catch (_: SecurityException) {
            toast(R.string.toast_device_address_permission)
            return
        }
        sendServiceAction(HidForegroundService.ACTION_CONNECT_DEVICE) {
            putExtra(HidForegroundService.EXTRA_DEVICE_ADDRESS, address)
        }
    }

    private fun render() {
        val adapterAvailable = bluetoothAdapter != null
        val permissionsGranted = hasBluetoothPermissions()
        val bluetoothEnabled = permissionsGranted && isBluetoothEnabled()
        val state = HidForegroundService.snapshot()
        val overlayGranted = Settings.canDrawOverlays(this)

        grantBluetoothButton.visibility = if (permissionsGranted) View.GONE else View.VISIBLE
        enableBluetoothButton.visibility = if (permissionsGranted && !bluetoothEnabled) View.VISIBLE else View.GONE

        startPairingButton.isEnabled = adapterAvailable && permissionsGranted && bluetoothEnabled
        discoverAgainButton.isEnabled = state.registered && bluetoothEnabled

        grantOverlayButton.visibility = if (overlayGranted) View.GONE else View.VISIBLE
        grantOverlayButton.isEnabled = state.connected
        showOverlayButton.visibility =
            if (overlayGranted && !state.overlayVisible) View.VISIBLE else View.GONE
        showOverlayButton.isEnabled = state.connected
        hideOverlayButton.visibility = if (state.overlayVisible) View.VISIBLE else View.GONE
        stopServiceButton.visibility = if (state.serviceRunning) View.VISIBLE else View.GONE

        val message = when {
            !adapterAvailable -> getString(R.string.unsupported_bluetooth)
            !permissionsGranted -> getString(R.string.status_allow_nearby_devices)
            !bluetoothEnabled -> getString(R.string.status_enable_bluetooth)
            state.serviceRunning -> state.message.resolve(this)
            else -> getString(R.string.status_ready_to_pair)
        }
        statusText.text = message
        val needsAttention = !adapterAvailable || !permissionsGranted || !bluetoothEnabled
        val labelText = when {
            state.isError -> R.string.status_error_label
            state.connected -> R.string.status_connected_label
            needsAttention -> R.string.status_attention_label
            else -> R.string.status_ready_label
        }
        val supportText = when {
            state.isError -> R.string.status_support_error
            state.connected -> R.string.status_support_connected
            needsAttention -> R.string.status_support_attention
            else -> R.string.status_support_ready
        }
        val labelBackground = when {
            state.isError -> R.color.error_container
            state.connected -> R.color.primary_container
            needsAttention -> R.color.secondary_container
            else -> R.color.primary_container
        }
        val labelForeground = when {
            state.isError -> R.color.on_error_container
            needsAttention -> R.color.on_secondary_container
            else -> R.color.on_primary_container
        }
        statusLabel.setText(labelText)
        statusLabel.backgroundTintList = ColorStateList.valueOf(getColor(labelBackground))
        statusLabel.setTextColor(getColor(labelForeground))
        statusSupportText.setText(supportText)
        statusText.setTextColor(getColor(if (state.isError) R.color.error else R.color.on_surface))
    }

    private fun loadBondedDevices() {
        pairedDevicesContainer.removeAllViews()
        if (!hasBluetoothPermissions() || !isBluetoothEnabled()) {
            addEmptyDeviceMessage()
            return
        }

        val devices = try {
            bluetoothAdapter?.bondedDevices.orEmpty().sortedBy { safeName(it).lowercase() }
        } catch (_: SecurityException) {
            emptyList()
        }
        if (devices.isEmpty()) {
            addEmptyDeviceMessage()
            return
        }

        devices.forEach { device ->
            val button = layoutInflater.inflate(
                R.layout.item_paired_device,
                pairedDevicesContainer,
                false,
            ) as MaterialButton
            button.apply {
                text = getString(
                    R.string.paired_device_label,
                    safeName(device),
                    safeAddress(device),
                )
                setOnClickListener { connectDevice(device) }
            }
            pairedDevicesContainer.addView(button)
        }
    }

    private fun addEmptyDeviceMessage() {
        pairedDevicesContainer.addView(TextView(this).apply {
            text = getString(R.string.no_paired_devices)
            setTextColor(getColor(R.color.on_surface_variant))
            textSize = 14f
            setPadding(0, dp(10), 0, dp(4))
        })
    }

    private fun hasBluetoothPermissions(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED)

    private fun isBluetoothEnabled(): Boolean = try {
        bluetoothAdapter?.isEnabled == true
    } catch (_: SecurityException) {
        false
    }

    private fun safeName(device: BluetoothDevice): String = try {
        device.name?.takeIf { it.isNotBlank() } ?: getString(R.string.unnamed_device)
    } catch (_: SecurityException) {
        getString(R.string.unnamed_device)
    }

    private fun safeAddress(device: BluetoothDevice): String = try {
        device.address
    } catch (_: SecurityException) {
        getString(R.string.device_address_unavailable)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            render()
            loadBondedDevices()
            if (hasBluetoothPermissions()) {
                if (pendingDiscoverable && !isBluetoothEnabled()) requestEnableBluetooth()
                else resumePendingDiscoverability()
            } else {
                pendingDiscoverable = false
                pairingStartRequested = false
                toast(R.string.toast_bluetooth_permission_required)
            }
        }
    }

    private fun showLanguageDialog() {
        val localeOptions = listOf(
            LocaleListCompat.getEmptyLocaleList(),
            LocaleListCompat.forLanguageTags(LOCALE_SIMPLIFIED_CHINESE),
            LocaleListCompat.forLanguageTags(LOCALE_ENGLISH),
        )
        val labels = arrayOf(
            getString(R.string.language_follow_system),
            getString(R.string.language_simplified_chinese),
            getString(R.string.language_english),
        )
        val currentTags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        val selected = when {
            currentTags.isBlank() -> 0
            currentTags.startsWith("zh", ignoreCase = true) -> 1
            else -> 2
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.language_dialog_title)
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                val locales = localeOptions[which]
                dialog.dismiss()
                if (locales == AppCompatDelegate.getApplicationLocales()) return@setSingleChoiceItems
                AppCompatDelegate.setApplicationLocales(locales)
                if (HidForegroundService.running) {
                    sendServiceAction(HidForegroundService.ACTION_REFRESH_LOCALE)
                }
            }
            .show()
    }

    private fun toast(@StringRes message: Int) = toast(getString(message))

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "MainActivity"
        private const val LOCALE_SIMPLIFIED_CHINESE = "zh-CN"
        private const val LOCALE_ENGLISH = "en"
        private const val REQUEST_PERMISSIONS = 10
        private const val DISCOVERABLE_DURATION_SECONDS = 300
        private const val STATE_PENDING_DISCOVERABLE = "pending_discoverable"
        private const val STATE_PENDING_SHOW_OVERLAY = "pending_show_overlay"
    }
}
