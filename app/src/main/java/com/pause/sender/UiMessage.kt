package com.pause.sender

import android.content.Context
import androidx.annotation.StringRes

data class UiMessage(
    @StringRes val resourceId: Int,
    val arguments: List<String> = emptyList(),
) {
    fun resolve(context: Context): String =
        context.getString(resourceId, *arguments.toTypedArray())

    companion object {
        fun of(@StringRes resourceId: Int, vararg arguments: String): UiMessage =
            UiMessage(resourceId, arguments.toList())
    }
}
