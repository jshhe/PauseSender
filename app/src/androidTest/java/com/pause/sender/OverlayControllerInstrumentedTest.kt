package com.pause.sender

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageButton
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class OverlayControllerInstrumentedTest {
    @Test
    fun overlayCanBeShownDisabledReenabledAndRemoved() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val holdMs = InstrumentationRegistry.getArguments()
            .getString("overlay_hold_ms")
            ?.toLongOrNull()
            ?.coerceIn(0L, 15_000L)
            ?: 0L
        val context = instrumentation.targetContext
        val visibilityChanges = CopyOnWriteArrayList<Boolean>()
        val errors = CopyOnWriteArrayList<UiMessage>()
        val keys = CopyOnWriteArrayList<Int>()
        val controller = OverlayController(
            context = context,
            onKey = keys::add,
            onVisibilityChanged = visibilityChanges::add,
            onError = errors::add,
        )

        try {
            instrumentation.runOnMainSync { controller.show(connected = true) }
            assertTrue("overlay should be attached", controller.isVisible)
            assertEquals(listOf(true), visibilityChanges)
            assertTrue("overlay creation should not report an error: $errors", errors.isEmpty())

            // Optional visual-QA window used by the emulator screenshot workflow.
            if (holdMs > 0L) Thread.sleep(holdMs)

            instrumentation.runOnMainSync {
                tap(checkNotNull(controller.actionButtonForTesting(HidReports.KEY_LEFT_ARROW)))
                tap(checkNotNull(controller.actionButtonForTesting(HidReports.KEY_SPACE)))
                tap(checkNotNull(controller.actionButtonForTesting(HidReports.KEY_RIGHT_ARROW)))
            }
            assertEquals(
                listOf(
                    HidReports.KEY_LEFT_ARROW,
                    HidReports.KEY_SPACE,
                    HidReports.KEY_RIGHT_ARROW,
                ),
                keys,
            )

            instrumentation.runOnMainSync {
                controller.setConnected(false)
                tap(checkNotNull(controller.actionButtonForTesting(HidReports.KEY_SPACE)))
                controller.setConnected(true)
                slideOutside(checkNotNull(controller.actionButtonForTesting(HidReports.KEY_SPACE)))
            }
            assertEquals("disabled and cancelled touches must not emit keys", 3, keys.size)
        } finally {
            instrumentation.runOnMainSync { controller.hide() }
        }

        assertFalse("overlay should be detached", controller.isVisible)
        assertEquals(listOf(true, false), visibilityChanges)
    }

    @Test
    @SdkSuppress(minSdkVersion = 30)
    fun overlayCanReachTopWhenStatusBarIsHiddenAndAvoidItWhenShown() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val preferences = context.getSharedPreferences("pause_sender", Context.MODE_PRIVATE)
        val savedX = preferences.all["overlay_x"] as? Int
        val savedY = preferences.all["overlay_y"] as? Int
        val errors = CopyOnWriteArrayList<UiMessage>()
        val controller = OverlayController(context, onKey = {}, onVisibilityChanged = {}, onError = errors::add)
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        val windowManager = context.getSystemService(WindowManager::class.java)
        val statusBars = WindowInsets.Type.statusBars()
        lateinit var overlay: ViewGroup

        try {
            instrumentation.runOnMainSync {
                activity.window.insetsController!!.hide(statusBars)
                controller.show(connected = true)
                overlay = checkNotNull(controller.actionButtonForTesting(HidReports.KEY_SPACE)).parent as ViewGroup
            }
            awaitOnMain("status bar should be hidden") {
                activity.window.decorView.rootWindowInsets?.isVisible(statusBars) == false
            }
            repeat(2) { cycle ->
                instrumentation.runOnMainSync {
                    val handle = overlay.getChildAt(0)
                    val downTime = SystemClock.uptimeMillis()
                    val x = handle.width / 2f
                    val y = handle.height / 2f
                    dispatch(handle, downTime, downTime, MotionEvent.ACTION_DOWN, x, y)
                    dispatch(handle, downTime, downTime + 10L, MotionEvent.ACTION_MOVE, x, -10_000f)
                    dispatch(handle, downTime, downTime + 20L, MotionEvent.ACTION_UP, x, -10_000f)
                }
                val cutoutTop = windowManager.currentWindowMetrics.windowInsets
                    .getInsets(WindowInsets.Type.displayCutout()).top
                val location = IntArray(2)
                awaitOnMain("hidden status bar must not reserve space above the overlay") {
                    overlay.getLocationOnScreen(location)
                    location[1] == cutoutTop
                }

                if (cycle == 0) {
                    instrumentation.runOnMainSync { activity.window.insetsController!!.show(statusBars) }
                    awaitOnMain("overlay should move below the visible status bar without a drag") {
                        val insets = windowManager.currentWindowMetrics.windowInsets
                        val safeTop = insets.getInsets(statusBars or WindowInsets.Type.displayCutout()).top
                        overlay.getLocationOnScreen(location)
                        activity.window.decorView.rootWindowInsets?.isVisible(statusBars) == true &&
                            safeTop > 0 && location[1] == safeTop
                    }
                    instrumentation.runOnMainSync { activity.window.insetsController!!.hide(statusBars) }
                    awaitOnMain("status bar should hide again") {
                        activity.window.decorView.rootWindowInsets?.isVisible(statusBars) == false
                    }
                }
            }
            assertTrue("overlay movement should not report errors: $errors", errors.isEmpty())
        } finally {
            instrumentation.runOnMainSync {
                controller.hide()
                preferences.edit(commit = true) {
                    if (savedX == null) remove("overlay_x") else putInt("overlay_x", savedX)
                    if (savedY == null) remove("overlay_y") else putInt("overlay_y", savedY)
                }
                activity.finish()
            }
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 30)
    fun overlayCanMoveBeyondFreeformActivityBounds() {
        assumeTrue(
            "Run with -e test_freeform true on a device with freeform windows enabled",
            InstrumentationRegistry.getArguments().getString("test_freeform") == "true",
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val displayBounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val smallBounds = Rect(
            displayBounds.width() / 4,
            displayBounds.height() / 4,
            displayBounds.width() * 3 / 4,
            displayBounds.height() * 3 / 4,
        )
        val preferences = context.getSharedPreferences("pause_sender", Context.MODE_PRIVATE)
        val savedX = preferences.all["overlay_x"] as? Int
        val savedY = preferences.all["overlay_y"] as? Int
        val errors = CopyOnWriteArrayList<UiMessage>()
        var activity: MainActivity? = null
        var controller: OverlayController? = null
        lateinit var overlay: ViewGroup

        try {
            shell("am start -W --windowingMode 5 -n com.pause.sender/.MainActivity")
            awaitOnMain("activity should launch in freeform mode") {
                activity = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()
                activity?.isInMultiWindowMode == true
            }
            val taskId = checkNotNull(activity).taskId
            shell("am task resize $taskId ${smallBounds.left} ${smallBounds.top} ${smallBounds.right} ${smallBounds.bottom}")
            awaitOnMain("activity should occupy only the middle of the display") {
                activity = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()
                activity?.windowManager?.currentWindowMetrics?.bounds == smallBounds
            }
            instrumentation.runOnMainSync {
                Log.i("OverlayRegression", "task=$smallBounds application=${context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds} display=$displayBounds")
                controller = OverlayController(context, onKey = {}, onVisibilityChanged = {}, onError = errors::add)
                controller!!.show(connected = true)
                overlay = checkNotNull(controller!!.actionButtonForTesting(HidReports.KEY_SPACE)).parent as ViewGroup
            }
            awaitOnMain("overlay should be laid out") { overlay.width > 0 && overlay.height > 0 }
            val location = IntArray(2)
            instrumentation.runOnMainSync { dragOverlay(overlay, -10_000f, -10_000f) }
            awaitOnMain("overlay must move to the left of the activity, not stop at its edge") {
                overlay.getLocationOnScreen(location)
                location[0] >= displayBounds.left && location[0] < smallBounds.left
            }
            instrumentation.runOnMainSync { dragOverlay(overlay, 10_000f, 10_000f) }
            awaitOnMain("overlay must move beyond the activity's right and bottom edges") {
                overlay.getLocationOnScreen(location)
                location[0] + overlay.width > smallBounds.right && location[0] + overlay.width <= displayBounds.right &&
                    location[1] + overlay.height > smallBounds.bottom && location[1] + overlay.height <= displayBounds.bottom
            }
            Log.i("OverlayRegression", "outside task: overlay=(${location[0]},${location[1]}) size=${overlay.width}x${overlay.height}")
            assertTrue("freeform overlay should not report errors: $errors", errors.isEmpty())
        } finally {
            instrumentation.runOnMainSync {
                controller?.hide()
                preferences.edit(commit = true) {
                    if (savedX == null) remove("overlay_x") else putInt("overlay_x", savedX)
                    if (savedY == null) remove("overlay_y") else putInt("overlay_y", savedY)
                }
                activity?.finishAndRemoveTask()
            }
        }
    }

    private fun shell(command: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
        }

    private fun dragOverlay(overlay: ViewGroup, x: Float, y: Float) {
        val handle = overlay.getChildAt(0)
        val downTime = SystemClock.uptimeMillis()
        dispatch(handle, downTime, downTime, MotionEvent.ACTION_DOWN, handle.width / 2f, handle.height / 2f)
        dispatch(handle, downTime, downTime + 10L, MotionEvent.ACTION_MOVE, x, y)
        dispatch(handle, downTime, downTime + 20L, MotionEvent.ACTION_UP, x, y)
    }

    private fun awaitOnMain(message: String, condition: () -> Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + 5_000L
        var satisfied = false
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync { satisfied = condition() }
            if (satisfied) return
            SystemClock.sleep(50L)
        }
        assertTrue(message, satisfied)
    }

    private fun tap(button: ImageButton) {
        val x = button.width.coerceAtLeast(1) / 2f
        val y = button.height.coerceAtLeast(1) / 2f
        val downTime = SystemClock.uptimeMillis()
        dispatch(button, downTime, downTime, MotionEvent.ACTION_DOWN, x, y)
        dispatch(button, downTime, downTime + 10L, MotionEvent.ACTION_UP, x, y)
    }

    private fun slideOutside(button: ImageButton) {
        val x = button.width.coerceAtLeast(1) / 2f
        val y = button.height.coerceAtLeast(1) / 2f
        val outsideX = button.width.coerceAtLeast(1) + 200f
        val downTime = SystemClock.uptimeMillis()
        dispatch(button, downTime, downTime, MotionEvent.ACTION_DOWN, x, y)
        dispatch(button, downTime, downTime + 10L, MotionEvent.ACTION_MOVE, outsideX, y)
        dispatch(button, downTime, downTime + 20L, MotionEvent.ACTION_UP, outsideX, y)
    }

    private fun dispatch(
        view: View,
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
    ) {
        MotionEvent.obtain(downTime, eventTime, action, x, y, 0).also { event ->
            try {
                view.dispatchTouchEvent(event)
            } finally {
                event.recycle()
            }
        }
    }
}
