package com.crdp.feature.connections

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.crdp.core.rdp.model.AudioMode
import com.crdp.core.rdp.model.AudioQuality
import com.crdp.core.rdp.model.CameraMode
import com.crdp.core.rdp.model.ConnectionProfile
import com.crdp.core.rdp.model.DirectConnectionProfile
import com.crdp.core.rdp.model.GatewayConnectionProfile
import com.crdp.core.rdp.model.VaultEntry
import com.crdp.core.rdp.repository.ProfileRepository
import com.crdp.core.rdp.repository.VaultRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject

enum class EditorMode { Direct, Gateway }

data class EditorUiState(
    val mode: EditorMode = EditorMode.Direct,
    val displayName: String = "",
    val host: String = "",
    val port: String = "3389",
    val gatewayBaseUrl: String = "http://10.0.2.2:8080",
    val targetHost: String = "",
    val targetPort: String = "3389",
    val bearerToken: String = "",
    val width: String = "1280",
    val height: String = "720",
    val colorDepth: Int = 32,
    val autoResolution: Boolean = false,
    /** "" or "0" means "use the app default DPI". Otherwise a percent in [100, 500]. */
    val desktopScaleFactor: String = "",
    /**
     * Vault entry the connection draws its credentials from. Null = no saved
     * credentials; the user will be prompted via [com.crdp.core.rdp.engine.EngineChallenge.Auth]
     * at connect time.
     */
    val vaultEntryId: String? = null,
    val audioMode: AudioMode = AudioMode.UseAppDefault,
    /** null = use app default; true/false = explicit override. */
    val microphoneOverride: Boolean? = null,
    /** null = use app default; plain-text clipboard with remote (direct RDP). */
    val clipboardSyncOverride: Boolean? = null,
    /** null = use app default; expose a virtual printer to the remote session. */
    val printerShareOverride: Boolean? = null,
    val audioQuality: AudioQuality = AudioQuality.UseAppDefault,
    val cameraMode: CameraMode = CameraMode.UseAppDefault,
    /** Native rdpecam device id when cameraMode == Specific; null otherwise. */
    val cameraDeviceId: String? = null,
    val isNew: Boolean = true,
    val existingId: String? = null,
)

@HiltViewModel
class ConnectionEditorViewModel @Inject constructor(
    private val repository: ProfileRepository,
    private val vaultRepository: VaultRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val rawProfileId: String =
        savedStateHandle.get<String>("profileId")
            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
            ?: "new"

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    /** Live list of vault entries for the credential picker dropdown. */
    val vaultEntries: StateFlow<List<VaultEntry>> = vaultRepository.entries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        if (rawProfileId != "new") {
            viewModelScope.launch {
                val existing = repository.getById(rawProfileId)
                if (existing != null) {
                    _state.update { it.fromProfile(existing, existing.id) }
                }
            }
        }
    }

    fun setMode(mode: EditorMode) {
        _state.update { it.copy(mode = mode) }
    }

    fun updateDisplayName(value: String) = _state.update { it.copy(displayName = value) }
    fun updateHost(value: String) = _state.update { it.copy(host = value) }
    fun updatePort(value: String) = _state.update { it.copy(port = value) }
    fun updateGatewayBaseUrl(value: String) = _state.update { it.copy(gatewayBaseUrl = value) }
    fun updateTargetHost(value: String) = _state.update { it.copy(targetHost = value) }
    fun updateTargetPort(value: String) = _state.update { it.copy(targetPort = value) }
    fun updateBearer(value: String) = _state.update { it.copy(bearerToken = value) }
    fun updateWidth(value: String) = _state.update { it.copy(width = value) }
    fun updateHeight(value: String) = _state.update { it.copy(height = value) }
    fun updateAutoResolution(value: Boolean) = _state.update { it.copy(autoResolution = value) }
    fun updateDesktopScaleFactor(value: String) =
        _state.update { it.copy(desktopScaleFactor = value.filter { c -> c.isDigit() }.take(3)) }
    fun updateColorDepth(value: Int) = _state.update { it.copy(colorDepth = value) }
    fun updateVaultEntryId(value: String?) = _state.update { it.copy(vaultEntryId = value) }
    fun updateAudioMode(value: AudioMode) = _state.update { it.copy(audioMode = value) }
    fun updateMicrophoneOverride(value: Boolean?) = _state.update { it.copy(microphoneOverride = value) }
    fun updateClipboardSyncOverride(value: Boolean?) = _state.update { it.copy(clipboardSyncOverride = value) }
    fun updatePrinterShareOverride(value: Boolean?) = _state.update { it.copy(printerShareOverride = value) }
    fun updateAudioQuality(value: AudioQuality) = _state.update { it.copy(audioQuality = value) }
    fun updateCameraMode(value: CameraMode) = _state.update { it.copy(cameraMode = value) }
    fun updateCameraDeviceId(value: String?) = _state.update { it.copy(cameraDeviceId = value?.takeIf { v -> v.isNotBlank() }) }

    private var newProfileDefaultsApplied = false

    /**
     * Seed a brand-new profile's resolution fields from the app-level "Default
     * resolution" setting. No-op once applied or for an existing profile that
     * was hydrated from the repo.
     */
    fun applyNewProfileDefaults(autoResolution: Boolean, width: Int, height: Int) {
        if (newProfileDefaultsApplied) return
        if (!_state.value.isNew) return
        newProfileDefaultsApplied = true
        _state.update {
            it.copy(
                autoResolution = autoResolution,
                width = width.toString(),
                height = height.toString(),
            )
        }
    }

    /**
     * Persist [entry] (assigning an id when blank) and immediately link it to the
     * current editor state. Invoked from the "Add new credential" inline dialog.
     */
    fun saveAndSelectVaultEntry(entry: VaultEntry) {
        viewModelScope.launch {
            val finalId = entry.id.ifBlank { UUID.randomUUID().toString() }
            vaultRepository.upsert(entry.copy(id = finalId))
            _state.update { it.copy(vaultEntryId = finalId) }
        }
    }

    fun save(onDone: (String) -> Unit) {
        viewModelScope.launch {
            val s = _state.value
            val id = s.existingId ?: UUID.randomUUID().toString()
            val scale = parseDesktopScaleField(s.desktopScaleFactor)
            val profile: ConnectionProfile = when (s.mode) {
                EditorMode.Direct -> DirectConnectionProfile(
                    id = id,
                    displayName = s.displayName.ifBlank { s.host.ifBlank { "Direct session" } },
                    host = s.host.trim(),
                    port = s.port.toIntOrNull() ?: 3389,
                    vaultEntryId = s.vaultEntryId,
                    // Credentials live in the vault now — these inline fields stay
                    // empty and the repository strips them on persist. Keeping the
                    // model fields nullable lets legacy on-disk profiles still load.
                    username = "",
                    domain = null,
                    password = "",
                    width = s.width.toIntOrNull() ?: 1280,
                    height = s.height.toIntOrNull() ?: 720,
                    colorDepth = s.colorDepth,
                    autoResolution = s.autoResolution,
                    desktopScaleFactor = scale,
                    requireBiometric = false,
                    audioMode = s.audioMode,
                    microphoneOverride = s.microphoneOverride,
                    clipboardSyncOverride = s.clipboardSyncOverride,
                    audioQuality = s.audioQuality,
                    cameraMode = s.cameraMode,
                    cameraDeviceId = s.cameraDeviceId,
                    printerShareOverride = s.printerShareOverride,
                )
                EditorMode.Gateway -> GatewayConnectionProfile(
                    id = id,
                    displayName = s.displayName.ifBlank { s.targetHost.ifBlank { "Gateway session" } },
                    gatewayBaseUrl = s.gatewayBaseUrl.trim().trimEnd('/'),
                    targetHost = s.targetHost.trim(),
                    targetPort = s.targetPort.toIntOrNull() ?: 3389,
                    bearerToken = s.bearerToken.trim().ifBlank { null },
                    width = s.width.toIntOrNull() ?: 1280,
                    height = s.height.toIntOrNull() ?: 720,
                    autoResolution = s.autoResolution,
                    desktopScaleFactor = scale,
                    requireBiometric = false,
                    audioMode = s.audioMode,
                    microphoneOverride = s.microphoneOverride,
                    clipboardSyncOverride = s.clipboardSyncOverride,
                    audioQuality = s.audioQuality,
                    cameraMode = s.cameraMode,
                    cameraDeviceId = s.cameraDeviceId,
                    printerShareOverride = s.printerShareOverride,
                )
            }
            repository.upsert(profile)
            onDone(profile.id)
        }
    }

    private fun parseDesktopScaleField(raw: String): Int {
        val n = raw.trim().toIntOrNull() ?: return 0
        if (n == 0) return 0
        return n.coerceIn(100, 500)
    }

    private fun EditorUiState.fromProfile(p: ConnectionProfile, id: String): EditorUiState = when (p) {
        is DirectConnectionProfile -> EditorUiState(
            mode = EditorMode.Direct,
            displayName = p.displayName,
            host = p.host,
            port = p.port.toString(),
            vaultEntryId = p.vaultEntryId,
            width = p.width.toString(),
            height = p.height.toString(),
            colorDepth = p.colorDepth,
            autoResolution = p.autoResolution,
            desktopScaleFactor = if (p.desktopScaleFactor in 100..500) p.desktopScaleFactor.toString() else "",
            audioMode = p.audioMode,
            microphoneOverride = p.microphoneOverride,
            clipboardSyncOverride = p.clipboardSyncOverride,
            audioQuality = p.audioQuality,
            cameraMode = p.cameraMode,
            cameraDeviceId = p.cameraDeviceId,
            printerShareOverride = p.printerShareOverride,
            isNew = false,
            existingId = id,
        )
        is GatewayConnectionProfile -> EditorUiState(
            mode = EditorMode.Gateway,
            displayName = p.displayName,
            gatewayBaseUrl = p.gatewayBaseUrl,
            targetHost = p.targetHost,
            targetPort = p.targetPort.toString(),
            bearerToken = p.bearerToken.orEmpty(),
            width = p.width.toString(),
            height = p.height.toString(),
            autoResolution = p.autoResolution,
            desktopScaleFactor = if (p.desktopScaleFactor in 100..500) p.desktopScaleFactor.toString() else "",
            audioMode = p.audioMode,
            microphoneOverride = p.microphoneOverride,
            clipboardSyncOverride = p.clipboardSyncOverride,
            audioQuality = p.audioQuality,
            cameraMode = p.cameraMode,
            cameraDeviceId = p.cameraDeviceId,
            printerShareOverride = p.printerShareOverride,
            isNew = false,
            existingId = id,
        )
    }
}
