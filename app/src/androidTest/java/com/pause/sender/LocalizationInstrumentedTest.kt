package com.pause.sender

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalizationInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun chineseAndEnglishResourcesResolveMessagesAndQuantities() {
        val chinese = context.withLocales("zh-CN")
        val english = context.withLocales("en")
        val connected = UiMessage.of(R.string.state_connected_device, "Office PC")

        assertEquals("已连接 Office PC，可以开启悬浮按钮", connected.resolve(chinese))
        assertEquals(
            "Connected to Office PC. You can now enable the floating controls",
            connected.resolve(english),
        )
        assertEquals(
            "This phone will be discoverable to computers for 1 second",
            english.resources.getQuantityString(R.plurals.discoverable_duration_seconds, 1, 1),
        )
        assertEquals(
            "This phone will be discoverable to computers for 300 seconds",
            english.resources.getQuantityString(R.plurals.discoverable_duration_seconds, 300, 300),
        )
    }

    @Test
    fun unsupportedLanguageFallsBackToSimplifiedChinese() {
        val french = context.withLocales("fr-FR")

        assertEquals("开始配对", french.getString(R.string.start_pairing))
    }

    private fun Context.withLocales(languageTags: String): Context {
        val configuration = Configuration(resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(languageTags))
        }
        return createConfigurationContext(configuration)
    }
}
