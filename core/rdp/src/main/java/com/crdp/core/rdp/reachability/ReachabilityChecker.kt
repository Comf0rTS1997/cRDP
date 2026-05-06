package com.crdp.core.rdp.reachability

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

data class ReachabilityProbe(
    val online: Boolean,
    val latencyMs: Long?,
)

object ReachabilityChecker {
    suspend fun probe(
        host: String,
        port: Int,
        timeoutMs: Int = 1500,
    ): ReachabilityProbe = withContext(Dispatchers.IO) {
        if (host.isBlank() || port !in 1..65535) {
            return@withContext ReachabilityProbe(online = false, latencyMs = null)
        }
        val started = System.currentTimeMillis()
        val ok = runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        }.getOrDefault(false)
        val elapsed = System.currentTimeMillis() - started
        ReachabilityProbe(online = ok, latencyMs = if (ok) elapsed else null)
    }
}
