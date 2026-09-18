package com.pause.sender

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.StringRes
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import kotlin.math.abs

class OverlayController(
    context: Context,
    private val onKey: (Int) -> Unit,
    private val onVisibilityChanged: (Boolean) -> Unit,
    private val onError: (UiMessage) -> Unit,
) {
    private val appContext = context.applicationContext
    // Overlay geometry must not inherit the activity's freeform or split-screen bounds.
    private val windowContext = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val display = appContext.getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
        appContext.createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    } else {
        appContext
    }
    private val windowManager = windowContext.getSystemService(WindowManager::class.java)
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var rootView: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var dragHandle: ImageView? = null
    private val actionButtons = linkedMapOf<Int, ImageButton>()
    private val accessibilityActionIds = mutableListOf<Int>()

    val isVisible: Boolean
        get() = rootView != null

    fun show(connected: Boolean) {
        if (rootView != null) {
            setConnected(connected)
            return
        }
        if (!Settings.canDrawOverlays(appContext)) {
            onError(UiMessage(R.string.error_overlay_permission_missing))
            return
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Keep screen coordinates; clampParams handles visible system bars.
                setFitInsetsTypes(0)
            }
            x = preferences.getInt(PREF_X, dp(16))
            y = preferences.getInt(PREF_Y, dp(180))
        }

        val container = LinearLayout(windowContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setBackgroundResource(R.drawable.bg_overlay)
            elevation = dp(14).toFloat()
            clipToPadding = false
            setOnApplyWindowInsetsListener { _, insets ->
                reclampPosition()
                insets
            }
        }

        val handle = ImageView(windowContext).apply {
            setImageResource(R.drawable.ic_drag_handle)
            contentDescription = localizedString(R.string.drag_overlay)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        dragHandle = handle
        attachDragListener(handle, params)
        container.addView(handle, LinearLayout.LayoutParams(dp(48), dp(56)))

        container.addView(createButton(R.drawable.ic_backward, R.string.backward, HidReports.KEY_LEFT_ARROW))
        container.addView(createButton(R.drawable.ic_pause, R.string.pause, HidReports.KEY_SPACE))
        container.addView(createButton(R.drawable.ic_forward, R.string.forward, HidReports.KEY_RIGHT_ARROW))
        container.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        clampParams(params, container.measuredWidth, container.measuredHeight)

        try {
            windowManager.addView(container, params)
            rootView = container
            layoutParams = params
            setConnected(connected)
            onVisibilityChanged(true)
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to add the overlay window", error)
            dragHandle = null
            actionButtons.clear()
            accessibilityActionIds.clear()
            onError(UiMessage(R.string.error_overlay_show_failed))
        }
    }

    private fun createButton(icon: Int, description: Int, usage: Int): ImageButton {
        return ImageButton(windowContext).apply {
            setImageResource(icon)
            contentDescription = localizedString(description)
            setBackgroundResource(
                if (usage == HidReports.KEY_SPACE) {
                    R.drawable.bg_overlay_primary_button
                } else {
                    R.drawable.bg_overlay_button
                }
            )
            imageTintList = ColorStateList.valueOf(
                if (usage == HidReports.KEY_SPACE) 0xFF17345D.toInt() else 0xFFFFFFFF.toInt()
            )
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                marginStart = dp(2)
                marginEnd = dp(2)
            }
            setOnClickListener { onKey(usage) }
            var clickCandidate = false
            val touchSlop = ViewConfiguration.get(windowContext).scaledTouchSlop.toFloat()
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        clickCandidate = view.isEnabled
                        view.animate().cancel()
                        view.animate().scaleX(0.91f).scaleY(0.91f).setDuration(80).start()
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (clickCandidate && !isPointInside(view, event.x, event.y, touchSlop)) {
                            clickCandidate = false
                            restoreButtonScale(view)
                        }
                        true
                    }

                    MotionEvent.ACTION_UP -> {
                        val shouldClick = clickCandidate &&
                            view.isEnabled &&
                            isPointInside(view, event.x, event.y, touchSlop)
                        clickCandidate = false
                        restoreButtonScale(view)
                        if (shouldClick) view.performClick()
                        true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        clickCandidate = false
                        restoreButtonScale(view)
                        true
                    }

                    else -> true
                }
            }
            actionButtons[usage] = this
        }
    }

    private fun attachDragListener(handle: View, params: WindowManager.LayoutParams) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        val touchSlop = ViewConfiguration.get(windowContext).scaledTouchSlop

        handle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val deltaX = (event.rawX - touchX).toInt()
                    val deltaY = (event.rawY - touchY).toInt()
                    if (abs(deltaX) > touchSlop || abs(deltaY) > touchSlop) moved = true
                    moveTo(params, startX + deltaX, startY + deltaY)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    reclampAndPersist(params)
                    if (event.actionMasked == MotionEvent.ACTION_UP && !moved) {
                        view.performClick()
                    }
                    true
                }

                else -> false
            }
        }
        addAccessibilityMoveActions(handle, params)
    }

    fun setConnected(connected: Boolean) {
        actionButtons.values.forEach { button ->
            button.isEnabled = connected
            button.alpha = if (connected) 1f else 0.38f
            if (!connected) {
                button.animate().cancel()
                button.scaleX = 1f
                button.scaleY = 1f
            }
        }
    }

    fun refreshLocalizedContent() {
        dragHandle?.let { handle ->
            handle.contentDescription = localizedString(R.string.drag_overlay)
            clearAccessibilityMoveActions(handle)
            layoutParams?.let { params -> addAccessibilityMoveActions(handle, params) }
        }
        actionButtons.forEach { (usage, button) ->
            button.contentDescription = localizedString(descriptionForUsage(usage))
        }
    }

    fun reclampPosition() {
        val view = rootView ?: return
        val params = layoutParams ?: return
        view.post {
            if (rootView !== view) return@post
            val previousX = params.x
            val previousY = params.y
            clampParams(params, view.width, view.height)
            if (params.x == previousX && params.y == previousY) return@post
            try {
                windowManager.updateViewLayout(view, params)
                persistPosition(params)
            } catch (_: RuntimeException) {
                // The overlay may have been detached during a configuration change.
            }
        }
    }

    internal fun actionButtonForTesting(usage: Int): ImageButton? = actionButtons[usage]

    fun hide() {
        val view = rootView ?: return
        try {
            windowManager.removeView(view)
        } catch (_: RuntimeException) {
            // It may already have been detached by the system.
        }
        rootView = null
        layoutParams = null
        dragHandle?.let(::clearAccessibilityMoveActions)
        dragHandle = null
        actionButtons.clear()
        onVisibilityChanged(false)
    }

    private fun restoreButtonScale(view: View) {
        view.animate().cancel()
        view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
    }

    private fun isPointInside(view: View, x: Float, y: Float, slop: Float): Boolean =
        x >= -slop &&
            y >= -slop &&
            x < view.width + slop &&
            y < view.height + slop

    private fun moveTo(params: WindowManager.LayoutParams, x: Int, y: Int) {
        val view = rootView ?: return
        params.x = x
        params.y = y
        clampParams(params, view.width, view.height)
        try {
            windowManager.updateViewLayout(view, params)
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to move the overlay window", error)
            onError(UiMessage(R.string.error_overlay_move_failed))
        }
    }

    private fun reclampAndPersist(params: WindowManager.LayoutParams) {
        val view = rootView
        clampParams(
            params,
            view?.width?.takeIf { it > 0 } ?: dp(DEFAULT_OVERLAY_WIDTH_DP),
            view?.height?.takeIf { it > 0 } ?: dp(DEFAULT_OVERLAY_HEIGHT_DP),
        )
        persistPosition(params)
    }

    private fun clampParams(
        params: WindowManager.LayoutParams,
        overlayWidth: Int,
        overlayHeight: Int,
    ) {
        val bounds = safeDisplayBounds()
        val position = clampOverlayPosition(
            x = params.x,
            y = params.y,
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom,
            overlayWidth = overlayWidth.coerceAtLeast(1),
            overlayHeight = overlayHeight.coerceAtLeast(1),
        )
        params.x = position.x
        params.y = position.y
    }

    private fun safeDisplayBounds(): Rect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsets(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            return Rect(
                metrics.bounds.left + insets.left,
                metrics.bounds.top + insets.top,
                metrics.bounds.right - insets.right,
                metrics.bounds.bottom - insets.bottom,
            )
        }
        @Suppress("DEPRECATION")
        val displayMetrics = DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
        return Rect(0, 0, displayMetrics.widthPixels, displayMetrics.heightPixels)
    }

    private fun persistPosition(params: WindowManager.LayoutParams) {
        preferences.edit {
            putInt(PREF_X, params.x)
            putInt(PREF_Y, params.y)
        }
    }

    private fun addAccessibilityMoveAction(
        handle: View,
        params: WindowManager.LayoutParams,
        label: Int,
        deltaXDp: Int,
        deltaYDp: Int,
    ) {
        val actionId = ViewCompat.addAccessibilityAction(handle, localizedString(label)) { _, _ ->
            moveTo(params, params.x + dp(deltaXDp), params.y + dp(deltaYDp))
            persistPosition(params)
            true
        }
        accessibilityActionIds += actionId
    }

    private fun addAccessibilityMoveActions(
        handle: View,
        params: WindowManager.LayoutParams,
    ) {
        addAccessibilityMoveAction(handle, params, R.string.move_overlay_left, -ACCESSIBILITY_MOVE_DP, 0)
        addAccessibilityMoveAction(handle, params, R.string.move_overlay_right, ACCESSIBILITY_MOVE_DP, 0)
        addAccessibilityMoveAction(handle, params, R.string.move_overlay_up, 0, -ACCESSIBILITY_MOVE_DP)
        addAccessibilityMoveAction(handle, params, R.string.move_overlay_down, 0, ACCESSIBILITY_MOVE_DP)
    }

    private fun clearAccessibilityMoveActions(handle: View) {
        accessibilityActionIds.forEach { actionId ->
            ViewCompat.removeAccessibilityAction(handle, actionId)
        }
        accessibilityActionIds.clear()
    }

    @StringRes
    private fun descriptionForUsage(usage: Int): Int = when (usage) {
        HidReports.KEY_LEFT_ARROW -> R.string.backward
        HidReports.KEY_SPACE -> R.string.pause
        HidReports.KEY_RIGHT_ARROW -> R.string.forward
        else -> error("Unsupported overlay key: $usage")
    }

    private fun localizedString(@StringRes resourceId: Int): String =
        appContext.forAppLocale().getString(resourceId)

    private fun dp(value: Int): Int =
        (value * windowContext.resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "OverlayController"
        private const val PREFS_NAME = "pause_sender"
        private const val PREF_X = "overlay_x"
        private const val PREF_Y = "overlay_y"
        private const val ACCESSIBILITY_MOVE_DP = 48
        private const val DEFAULT_OVERLAY_WIDTH_DP = 228
        private const val DEFAULT_OVERLAY_HEIGHT_DP = 68
    }
}
