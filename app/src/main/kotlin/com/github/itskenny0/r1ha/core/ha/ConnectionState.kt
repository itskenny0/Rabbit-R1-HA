package com.github.itskenny0.r1ha.core.ha

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data object Connecting : ConnectionState
    data object Authenticating : ConnectionState
    data class Connected(val haVersion: String?) : ConnectionState
    data class Disconnected(val cause: Cause, val attempt: Int) : ConnectionState
    data class AuthLost(val reason: String?) : ConnectionState

    sealed interface Cause {
        data object Network : Cause
        data object ServerClosed : Cause
        data class Error(val throwable: Throwable) : Cause
    }
}
