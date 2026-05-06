package com.crdp.feature.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.crdp.core.rdp.model.ConnectionProfile
import com.crdp.core.rdp.reachability.ReachabilityRepository
import com.crdp.core.rdp.repository.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConnectionListViewModel @Inject constructor(
    private val repository: ProfileRepository,
    private val reachabilityRepository: ReachabilityRepository,
) : ViewModel() {

    val profiles = repository.profiles.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList(),
    )

    val reachability = reachabilityRepository.state.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyMap(),
    )

    init {
        repository.profiles
            .onEach { reachabilityRepository.startPolling(it) }
            .launchIn(viewModelScope)
    }

    override fun onCleared() {
        super.onCleared()
        reachabilityRepository.stopPolling()
    }

    fun delete(profile: ConnectionProfile) {
        viewModelScope.launch {
            repository.delete(profile.id)
        }
    }
}
