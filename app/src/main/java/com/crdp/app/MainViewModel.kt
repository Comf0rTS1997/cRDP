package com.crdp.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.crdp.app.data.VaultEncryptionState
import com.crdp.app.prefs.AppSettings
import com.crdp.app.prefs.AutoLockVault
import com.crdp.app.prefs.UserPreferencesRepository
import com.crdp.core.rdp.model.ConnectionProfile
import com.crdp.core.rdp.model.DirectConnectionProfile
import com.crdp.core.rdp.model.GatewayConnectionProfile
import com.crdp.core.rdp.repository.ProfileRepository
import com.crdp.core.rdp.repository.VaultRepository
import kotlinx.coroutines.flow.first
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val profileRepository: ProfileRepository,
    private val vaultRepository: VaultRepository,
    private val vaultEncryptionState: VaultEncryptionState,
) : ViewModel() {

    init {
        // The repository singleton needs the on-disk mode the moment any read happens.
        // Hilt has already constructed prefs, but the encryption flag lives in DataStore
        // (suspend), so seed the volatile mirror once on startup before any vault I/O.
        viewModelScope.launch {
            vaultEncryptionState.encrypted =
                userPreferencesRepository.appSettings.first().vaultEncryption
        }
    }

    suspend fun getProfile(id: String): ConnectionProfile? = profileRepository.getById(id)

    /**
     * True when the connection references a vault entry that holds credentials — the
     * only case where the UI biometric gate actually has something to guard. Profiles
     * with no vault link (gateway, or direct without saved credentials) skip the prompt.
     */
    suspend fun referencesVaultCredentials(profile: ConnectionProfile): Boolean {
        if (profile !is DirectConnectionProfile) return false
        val entryId = profile.vaultEntryId ?: return false
        val entry = vaultRepository.getById(entryId) ?: return false
        return entry.password.isNotEmpty() || entry.username.isNotEmpty()
    }

    val dynamicColor = userPreferencesRepository.dynamicColor.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = true,
    )

    val appSettings = userPreferencesRepository.appSettings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AppSettings(),
    )

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setDynamicColor(enabled) }
    }

    fun setTouchAsMouse(value: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setTouchAsMouse(value) }
    }

    fun setHapticFeedback(value: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setHapticFeedback(value) }
    }

    fun setAutoDisconnectIdle(value: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setAutoDisconnectIdle(value) }
    }

    fun setDefaultResolution(value: String) {
        viewModelScope.launch { userPreferencesRepository.setDefaultResolution(value) }
    }

    fun setKeyboardLayout(value: String) {
        viewModelScope.launch { userPreferencesRepository.setKeyboardLayout(value) }
    }

    fun setAutoLockVaultMinutes(value: Int) {
        viewModelScope.launch { userPreferencesRepository.setAutoLockVaultMinutes(value) }
    }

    fun setRenderBackend(value: String) {
        viewModelScope.launch { userPreferencesRepository.setRenderBackend(value) }
    }

    fun setRenderSampling(value: String) {
        viewModelScope.launch { userPreferencesRepository.setRenderSampling(value) }
    }

    fun setConnectionViewMode(value: String) {
        viewModelScope.launch { userPreferencesRepository.setConnectionViewMode(value) }
    }

    fun addCustomResolution(value: String) {
        viewModelScope.launch { userPreferencesRepository.addCustomResolution(value) }
    }

    fun removeCustomResolution(value: String) {
        viewModelScope.launch { userPreferencesRepository.removeCustomResolution(value) }
    }

    fun setDefaultDpiScale(value: Int) {
        viewModelScope.launch { userPreferencesRepository.setDefaultDpiScale(value) }
    }

    fun setDexDpiScale(value: Int) {
        viewModelScope.launch { userPreferencesRepository.setDexDpiScale(value) }
    }

    fun setDefaultAudioMode(value: String) {
        viewModelScope.launch { userPreferencesRepository.setDefaultAudioMode(value) }
    }

    fun setDefaultMicrophoneEnabled(value: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setDefaultMicrophoneEnabled(value) }
    }

    fun setDefaultAudioQuality(value: String) {
        viewModelScope.launch { userPreferencesRepository.setDefaultAudioQuality(value) }
    }

    fun setDefaultCameraMode(value: String) {
        viewModelScope.launch { userPreferencesRepository.setDefaultCameraMode(value) }
    }

    fun setDefaultCameraDeviceId(value: String?) {
        viewModelScope.launch { userPreferencesRepository.setDefaultCameraDeviceId(value) }
    }

    fun setDefaultClipboardSync(value: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setDefaultClipboardSync(value) }
    }

    fun setDefaultPrinterShare(value: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setDefaultPrinterShare(value) }
    }

    /**
     * Persists the new global vault-encryption preference AND rewrites the on-disk
     * vault file in the matching format. When toggling off, entries become plaintext
     * JSON; toggling back on re-wraps them in EncryptedFile.
     */
    fun setVaultEncryption(value: Boolean) {
        viewModelScope.launch {
            userPreferencesRepository.setVaultEncryption(value)
            vaultRepository.setEncryption(value)
            vaultEncryptionState.encrypted = value
        }
    }

    fun setAuxKeyRowKey(id: String, enabled: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setAuxKeyRowKey(id, enabled) }
    }

    private val _vaultUnlockedAt = MutableStateFlow<Long?>(null)
    val vaultUnlockedAt = _vaultUnlockedAt.asStateFlow()

    fun isVaultUnlocked(autoLockMinutes: Int, nowMs: Long = System.currentTimeMillis()): Boolean {
        val unlockedAt = _vaultUnlockedAt.value ?: return false
        return when (autoLockMinutes) {
            AutoLockVault.NEVER -> true
            AutoLockVault.IMMEDIATELY -> false
            else -> nowMs - unlockedAt < autoLockMinutes * 60_000L
        }
    }

    fun markVaultUnlocked() {
        _vaultUnlockedAt.value = System.currentTimeMillis()
    }

    fun lockVault() {
        _vaultUnlockedAt.value = null
    }
}
