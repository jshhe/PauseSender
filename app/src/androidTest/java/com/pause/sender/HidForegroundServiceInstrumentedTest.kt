package com.pause.sender

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HidForegroundServiceInstrumentedTest {
    @Test
    fun serviceCanRegisterStopAndRegisterAgain() {
        assumeTrue(
            "Run with -e test_hid true after enabling Bluetooth and granting permissions",
            InstrumentationRegistry.getArguments().getString("test_hid") == "true",
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        val serviceIntent = Intent(context, HidForegroundService::class.java)

        try {
            repeat(2) {
                instrumentation.runOnMainSync {
                    context.startForegroundService(Intent(serviceIntent).setAction(HidForegroundService.ACTION_START_HID))
                }
                awaitState("HID profile should become ready and register") {
                    val state = HidForegroundService.snapshot()
                    state.serviceRunning && state.profileReady && state.registered
                }
                assertFalse(HidForegroundService.snapshot().isError)
                instrumentation.runOnMainSync {
                    context.startService(Intent(serviceIntent).setAction(HidForegroundService.ACTION_STOP))
                }
                awaitState("service and report thread should stop") { isStopped() }
            }
        } finally {
            instrumentation.runOnMainSync { context.stopService(serviceIntent) }
            try {
                awaitState("test cleanup should stop service and report thread") { isStopped() }
            } finally {
                instrumentation.runOnMainSync { activity.finishAndRemoveTask() }
            }
        }
    }

    private fun isStopped(): Boolean =
        !HidForegroundService.running && !HidForegroundService.snapshot().serviceRunning &&
            Thread.getAllStackTraces().keys.none { it.name == "PauseSenderHidReports" && it.isAlive }

    private fun awaitState(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50L)
        }
        fail("$message; last state=${HidForegroundService.snapshot()}")
    }
}
