package com.crdp.feature.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.crdp.core.rdp.model.DirectConnectionProfile
import com.crdp.core.rdp.repository.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VaultViewModel @Inject constructor(
    private val repository: ProfileRepository,
) : ViewModel() {

    val savedCredentials: StateFlow<List<DirectConnectionProfile>> =
        repository.profiles
            .map { list ->
                list.filterIsInstance<DirectConnectionProfile>().filter { it.password.isNotEmpty() }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clearPassword(profileId: String) {
        viewModelScope.launch {
            val profile = repository.getById(profileId) as? DirectConnectionProfile ?: return@launch
            // Clearing the password means no ciphertext is written, so the requireUserAuth
            // flag is moot here — pass false to avoid pointlessly creating an auth-bound key.
            repository.upsert(profile.copy(password = ""), requireUserAuth = false)
        }
    }
}
