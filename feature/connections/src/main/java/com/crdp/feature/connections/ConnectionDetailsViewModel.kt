package com.crdp.feature.connections

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.crdp.core.rdp.model.ConnectionProfile
import com.crdp.core.rdp.reachability.ReachabilityProbe
import com.crdp.core.rdp.reachability.ReachabilityRepository
import com.crdp.core.rdp.repository.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject

@HiltViewModel
class ConnectionDetailsViewModel @Inject constructor(
    private val repository: ProfileRepository,
    private val reachabilityRepository: ReachabilityRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val profileId: String =
        savedStateHandle.get<String>("profileId")
            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
            ?: ""

    private val _profile = MutableStateFlow<ConnectionProfile?>(null)
    val profile: StateFlow<ConnectionProfile?> = _profile.asStateFlow()

    private val _reachability = MutableStateFlow<ReachabilityProbe?>(null)
    val reachability: StateFlow<ReachabilityProbe?> = _reachability.asStateFlow()

    init {
        viewModelScope.launch {
            val loaded = repository.getById(profileId)
            _profile.value = loaded
            _reachability.value = reachabilityRepository.current(profileId)
            if (loaded != null) {
                while (true) {
                    _reachability.value = reachabilityRepository.refreshOne(loaded)
                    delay(30_000L)
                }
            }
        }
    }

    fun delete(onDone: () -> Unit) {
        val current = _profile.value ?: return
        viewModelScope.launch {
            repository.delete(current.id)
            onDone()
        }
    }
}
