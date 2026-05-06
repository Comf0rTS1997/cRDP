package com.crdp.core.rdp.engine

import javax.inject.Qualifier

/**
 * Hilt qualifier for the [RdpEngine] used by direct (no-gateway) sessions.
 * A future @GatewayEngine could disambiguate a different binding for the gateway
 * transport without touching consumer code.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DirectEngine
