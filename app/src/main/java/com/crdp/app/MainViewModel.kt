package com.crdp.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.crdp.app.data.vault.DeviceKeySupport
import com.crdp.app.prefs.AppSettings
import com.crdp.app.prefs.AutoLockVault
import com.crdp.app.prefs.UserPreferencesRepository
import com.crdp.core.rdp.model.ConnectionProfile
import com.crdp.core.rdp.model.DirectConnectionProfile
import com.crdp.core.rdp.model.VaultProtection
import com.crdp.core.rdp.repository.ProfileRepository
import com.crdp.core.rdp.repository.SetProtectionOutcome
import com.crdp.core.rdp.repository.UnlockOutcome
import com.crdp.core.rdp.repository.VaultRepository
import com.crdp.core.rdp.repository.VaultStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val profileRepository: ProfileRepository,
    private val vaultRepository: VaultRepository,
) : ViewModel() {

    suspend fun getProfile(id: String): ConnectionProfile? = profileRepository.getById(id)

    /**
     * True when the connection points at a vault entry — i.e. credentials live
     * in the (potentially locked) vault rather than inline on the profile. The
     * UI uses this to decide whether opening the session needs a vault unlock
     * first. Returns false for gateway profiles and direct profiles that have
     * no vault reference.
     */
    fun referencesVaultCredentials(profile: ConnectionProfile): Boolean {
        if (profile !is DirectConnectionProfile) return false
        return profile.vaultEntryId != null
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

    val vaultStatus: kotlinx.coroutines.flow.StateFlow<VaultStatus> = vaultRepository.status.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = VaultStatus(VaultProtection.DeviceKey, unlocked = false, hasData = false),
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

    fun setReverseScroll(value: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setReverseScroll(value) }
    }

    fun setMouseWheelSpeed(value: Int) {
        viewModelScope.launch { userPreferencesRepository.setMouseWheelSpeed(value) }
    }

    fun setTouchpadScrollSpeed(value: Int) {
        viewModelScope.launch { userPreferencesRepository.setTouchpadScrollSpeed(value) }
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

    fun setNetworkAutoDetect(value: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setNetworkAutoDetect(value) }
    }

    fun setShowDesktopBackground(value: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setShowDesktopBackground(value) }
    }

    fun setWindowContentsWhileDragging(value: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setWindowContentsWhileDragging(value) }
    }

    fun setMenuAnimations(value: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setMenuAnimations(value) }
    }

    fun setGlyphCache(value: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setGlyphCache(value) }
    }

    fun setPreferredEncoder(value: String) {
        viewModelScope.launch { userPreferencesRepository.setPreferredEncoder(value) }
    }

    fun setShowCaptureHint(value: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setShowCaptureHint(value) }
    }

    /**
     * Asynchronously probes whether the device can actually host an
     * auth-bound Keystore key. Off the main thread because the probe does a
     * Keystore round-trip. The result drives whether the settings UI offers
     * DeviceKey mode or steers the user toward Password.
     */
    private val _deviceKeySupported = MutableStateFlow<Boolean?>(null)
    val deviceKeySupported = _deviceKeySupported.asStateFlow()

    init {
        viewModelScope.launch {
            _deviceKeySupported.value = withContext(Dispatchers.IO) {
                DeviceKeySupport.canHostAuthBoundKey(appContext)
            }
        }
    }

    private val _vaultProtectionResult = MutableStateFlow<SetProtectionOutcome?>(null)
    val vaultProtectionResult = _vaultProtectionResult.asStateFlow()

    /**
     * Switch the vault to a new protection mode and broadcast the outcome via
     * [vaultProtectionResult] so the settings screen can render an inline
     * error without needing a return value. The vault must already be unlocked
     * under the current mode; the repo refuses re-encryption otherwise.
     * [password] is required for [VaultProtection.Password] and is zeroed by
     * the repo regardless of outcome.
     */
    fun requestVaultProtection(target: VaultProtection, password: CharArray? = null) {
        viewModelScope.launch {
            _vaultProtectionResult.value = vaultRepository.setProtection(target, password)
        }
    }

    fun acknowledgeVaultProtectionResult() {
        _vaultProtectionResult.value = null
    }

    /**
     * Called after a successful biometric prompt. Triggers the actual vault
     * read under the auth-bound master key — the prompt itself only widened
     * the Keystore validity window; we have to do a real crypto op to confirm
     * the key works and pull entries into cache.
     */
    suspend fun completeDeviceKeyUnlock(): UnlockOutcome {
        val outcome = vaultRepository.tryUnlockWithDeviceKey()
        if (outcome == UnlockOutcome.Success) markVaultUnlocked()
        return outcome
    }

    suspend fun unlockWithPassword(password: CharArray): UnlockOutcome {
        val outcome = vaultRepository.tryUnlockWithPassword(password)
        if (outcome == UnlockOutcome.Success) markVaultUnlocked()
        return outcome
    }

    fun setAuxKeyRowKey(id: String, enabled: Boolean) {
        viewModelScope.launch { userPreferencesRepository.setAuxKeyRowKey(id, enabled) }
    }

    private val _vaultUnlockedAt = MutableStateFlow<Long?>(null)
    val vaultUnlockedAt = _vaultUnlockedAt.asStateFlow()

    fun isVaultUnlocked(autoLockMinutes: Int, nowMs: Long = System.currentTimeMillis()): Boolean {
        // For an unprotected vault there's nothing to gate.
        if (vaultStatus.value.protection == VaultProtection.None) return true
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
        viewModelScope.launch { vaultRepository.lock() }
    }
}
