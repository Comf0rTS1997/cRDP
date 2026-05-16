package com.crdp.feature.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.crdp.core.rdp.model.VaultEntry
import com.crdp.core.rdp.repository.VaultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class VaultViewModel @Inject constructor(
    private val repository: VaultRepository,
) : ViewModel() {

    val entries: StateFlow<List<VaultEntry>> =
        repository.entries
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(entryId: String) {
        viewModelScope.launch { repository.delete(entryId) }
    }

    /**
     * Persist an entry. When [entry.id] is blank a new UUID is generated; callers
     * use the returned id to attach the entry to a profile.
     */
    fun upsert(entry: VaultEntry, onSaved: (String) -> Unit = {}) {
        viewModelScope.launch {
            val finalId = entry.id.ifBlank { UUID.randomUUID().toString() }
            repository.upsert(entry.copy(id = finalId))
            onSaved(finalId)
        }
    }
}
