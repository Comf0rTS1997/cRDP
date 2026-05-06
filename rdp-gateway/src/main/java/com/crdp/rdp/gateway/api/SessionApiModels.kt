package com.crdp.rdp.gateway.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CreateSessionRequest(
    val targetHost: String,
    val targetPort: Int,
    val displayName: String,
)

@Serializable
data class CreateSessionResponse(
    val sessionId: String,
    val relayUrl: String,
    val expiresAt: String? = null,
)

@Serializable
data class SessionStatusResponse(
    val sessionId: String,
    val status: String,
)
