package com.pause.sender

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate

internal fun Context.forAppLocale(): Context {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return this
    val locales = AppCompatDelegate.getApplicationLocales()
    if (locales.isEmpty) return this

    val configuration = Configuration(resources.configuration).apply {
        setLocales(LocaleList.forLanguageTags(locales.toLanguageTags()))
    }
    return createConfigurationContext(configuration)
}
