package com.crdp.core.rdp.reachability

import com.crdp.core.rdp.model.ConnectionProfile
import com.crdp.core.rdp.model.DirectConnectionProfile
import com.crdp.core.rdp.model.GatewayConnectionProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ReachabilityRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<Map<String, ReachabilityProbe>>(emptyMap())
    val state: StateFlow<Map<String, ReachabilityProbe>> = _state.asStateFlow()

    private var pollJob: Job? = null

    /** Returns the current probe for [profileId], or `null` if not yet probed. */
    fun current(profileId: String): ReachabilityProbe? = _state.value[profileId]

    /** Begin polling reachability for the supplied profiles every [intervalMs]. */
    fun startPolling(profiles: List<ConnectionProfile>, intervalMs: Long = 30_000L) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (true) {
                refresh(profiles)
                delay(intervalMs)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    suspend fun refresh(profiles: List<ConnectionProfile>) {
        val results = profiles.map { profile ->
            scope.async {
                profile.id to probe(profile)
            }
        }.awaitAll().toMap()
        _state.value = _state.value + results
    }

    suspend fun refreshOne(profile: ConnectionProfile): ReachabilityProbe {
        val probe = probe(profile)
        _state.value = _state.value + (profile.id to probe)
        return probe
    }

    private suspend fun probe(profile: ConnectionProfile): ReachabilityProbe = when (profile) {
        is DirectConnectionProfile -> ReachabilityChecker.probe(profile.host, profile.port)
        is GatewayConnectionProfile -> ReachabilityChecker.probe(profile.targetHost, profile.targetPort)
    }
}
