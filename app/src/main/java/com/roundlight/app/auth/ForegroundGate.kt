package com.roundlight.app.auth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** Browser callbacks can arrive while Android restricts the app's background network access. */
class ForegroundGate {
    private val resumed = MutableStateFlow(false)
    fun setResumed(value: Boolean) { resumed.value = value }
    suspend fun awaitResumed() { resumed.first { it } }
}
