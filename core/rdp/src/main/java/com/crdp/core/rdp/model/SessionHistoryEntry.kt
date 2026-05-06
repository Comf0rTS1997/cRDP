package com.crdp.core.rdp.model

import kotlinx.serialization.Serializable

@Serializable
enum class SessionHistoryOutcome {
    Completed,
    Error,
}

@Serializable
data class SessionHistoryEntry(
    val id: String,
    val profileId: String,
    val displayName: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val bytesSent: Long = 0L,
    val bytesReceived: Long = 0L,
    val outcome: SessionHistoryOutcome = SessionHistoryOutcome.Completed,
)
