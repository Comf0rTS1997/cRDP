package com.crdp.app.session

import com.crdp.core.rdp.RdpSessionFactory
import com.crdp.core.rdp.RdpSessionPort
import com.crdp.core.rdp.model.ConnectionProfile
import com.crdp.core.rdp.model.DirectConnectionProfile
import com.crdp.core.rdp.model.GatewayConnectionProfile
import com.crdp.rdp.direct.DirectRdpSession
import com.crdp.rdp.gateway.GatewayRdpSession
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Hands out a fresh transport-specific RdpSessionPort per portFor() call.
 * Per-session lifecycle is required because each engine owns a worker thread
 * that blocks for the lifetime of the connection.
 */
@Singleton
class SessionCoordinator @Inject constructor(
    private val direct: Provider<DirectRdpSession>,
    private val gateway: Provider<GatewayRdpSession>,
) : RdpSessionFactory {
    override fun portFor(profile: ConnectionProfile): RdpSessionPort = when (profile) {
        is DirectConnectionProfile -> direct.get()
        is GatewayConnectionProfile -> gateway.get()
    }
}
