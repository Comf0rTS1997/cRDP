package com.crdp.core.rdp.model

sealed interface SessionState {
    data object Idle : SessionState
    data object Connecting : SessionState
    data class Connected(
        val detail: String,
        val bytesSent: Long = 0L,
        val bytesReceived: Long = 0L,
    ) : SessionState
    data object Disconnecting : SessionState
    /** Mid-session disconnect not initiated by the local user (server kick, drop, etc.). */
    data class Disconnected(val reason: String) : SessionState
    data class Error(val message: String) : SessionState
}
