package com.crdp.app.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class AppSettings(
    val touchAsMouse: Boolean = true,
    val hapticFeedback: Boolean = true,
    val biometricUnlock: Boolean = false,
    val autoDisconnectIdle: Boolean = false,
    val bandwidthProfile: String = "Adaptive",
    val defaultResolution: String = "Match device",
    val keyboardLayout: String = "US English",
    val autoLockVaultMinutes: Int = 5,
    val renderBackend: String = RenderBackends.AUTO,
    val renderSampling: String = RenderSamplingOptions.BILINEAR,
    val connectionViewMode: String = ConnectionViewModes.LIST,
    val customResolutions: List<String> = emptyList(),
    /** Default DPI scale percent, applied to new connections that don't override. 100..500. */
    val defaultDpiScale: Int = 100,
    /** DPI scale percent used when the device is in Samsung DeX (UI_MODE_TYPE_DESK). 100..500. */
    val dexDpiScale: Int = 100,
    val defaultAudioMode: String = AudioModes.LOCAL,
    val defaultMicrophoneEnabled: Boolean = false,
    val defaultAudioQuality: String = AudioQualities.DYNAMIC,
    val defaultCameraMode: String = CameraModes.OFF,
    /** Native rdpecam device id when defaultCameraMode == SPECIFIC; null/empty otherwise. */
    val defaultCameraDeviceId: String? = null,
    /** Encode camera frames to H.264 on device (true) vs send raw YUV and let server transcode. */
    val cameraEncode: Boolean = true,
)

object AudioModes {
    const val LOCAL = "Local device"
    const val REMOTE = "Remote PC"
    const val OFF = "Off"
    val OPTIONS = listOf(LOCAL, REMOTE, OFF)
}

object AudioQualities {
    const val DYNAMIC = "Dynamic"
    const val MEDIUM = "Medium"
    const val HIGH = "High"
    val OPTIONS = listOf(DYNAMIC, MEDIUM, HIGH)
}

object CameraModes {
    const val OFF = "Off"
    const val FRONT = "Front camera"
    const val BACK = "Back camera"
    const val EXTERNAL = "External (USB)"
    const val SPECIFIC = "Specific device"
    val OPTIONS = listOf(OFF, FRONT, BACK, EXTERNAL, SPECIFIC)
}

object ConnectionViewModes {
    const val LIST = "list"
    const val GRID = "grid"
}

object RenderBackends {
    const val AUTO = "Auto"
    const val HWUI = "HWUI Canvas"
    const val GLES = "GLES (high quality)"
    val OPTIONS = listOf(AUTO, HWUI, GLES)
}

object RenderSamplingOptions {
    const val NEAREST = "Nearest (sharp text)"
    const val BILINEAR = "Bilinear"
    const val LANCZOS = "Lanczos3 (sharper)"
    val OPTIONS = listOf(NEAREST, BILINEAR, LANCZOS)
}

object BandwidthProfiles {
    val OPTIONS = listOf("Adaptive", "LAN", "Cellular", "Custom")
}

object Resolutions {
    const val MATCH_DEVICE = "Match device"
    val BUILT_IN = listOf(MATCH_DEVICE, "1920x1080", "1280x720", "1024x768")
    val OPTIONS = BUILT_IN

    private val pattern = Regex("^\\s*(\\d{3,5})\\s*[x×*]\\s*(\\d{3,5})\\s*$", RegexOption.IGNORE_CASE)

    fun normalize(input: String): String? {
        val m = pattern.matchEntire(input) ?: return null
        val w = m.groupValues[1].toIntOrNull() ?: return null
        val h = m.groupValues[2].toIntOrNull() ?: return null
        if (w !in 320..7680 || h !in 240..4320) return null
        return "${w}x${h}"
    }
}

object KeyboardLayouts {
    val OPTIONS = listOf("US English", "UK English", "German", "French", "Japanese")
}

object DpiScales {
    /** Common DPI presets shown in the picker. Custom values are entered by hand. */
    val PRESETS = listOf(100, 125, 140, 150, 175, 200, 250, 300)
    const val MIN = 100
    const val MAX = 500
    fun normalize(input: String): Int? {
        val cleaned = input.trim().trimEnd('%').trim().toIntOrNull() ?: return null
        if (cleaned !in MIN..MAX) return null
        return cleaned
    }
    fun label(value: Int): String = "$value%"
}

object AutoLockVault {
    const val IMMEDIATELY = 0
    const val NEVER = -1
    val OPTIONS = listOf(IMMEDIATELY, 1, 5, 15, NEVER)
    fun label(value: Int): String = when (value) {
        IMMEDIATELY -> "Immediately"
        NEVER -> "Never"
        1 -> "1 minute"
        else -> "$value minutes"
    }
}

private val Context.userPrefs: DataStore<Preferences> by preferencesDataStore("crdp_user_prefs")

@Singleton
class UserPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val dynamicKey = booleanPreferencesKey("dynamic_color")
    private val touchAsMouseKey = booleanPreferencesKey("touch_as_mouse")
    private val hapticKey = booleanPreferencesKey("haptic_feedback")
    private val biometricKey = booleanPreferencesKey("biometric_unlock")
    private val autoDisconnectKey = booleanPreferencesKey("auto_disconnect_idle")
    private val bandwidthProfileKey = stringPreferencesKey("bandwidth_profile")
    private val defaultResolutionKey = stringPreferencesKey("default_resolution")
    private val keyboardLayoutKey = stringPreferencesKey("keyboard_layout")
    private val autoLockVaultKey = intPreferencesKey("auto_lock_vault_minutes")
    private val renderBackendKey = stringPreferencesKey("render_backend")
    private val renderSamplingKey = stringPreferencesKey("render_sampling")
    private val connectionViewModeKey = stringPreferencesKey("connection_view_mode")
    private val customResolutionsKey = stringSetPreferencesKey("custom_resolutions")
    private val defaultDpiScaleKey = intPreferencesKey("default_dpi_scale")
    private val dexDpiScaleKey = intPreferencesKey("dex_dpi_scale")
    private val defaultAudioModeKey = stringPreferencesKey("default_audio_mode")
    private val defaultMicrophoneEnabledKey = booleanPreferencesKey("default_microphone_enabled")
    private val defaultAudioQualityKey = stringPreferencesKey("default_audio_quality")
    private val defaultCameraModeKey = stringPreferencesKey("default_camera_mode")
    private val defaultCameraDeviceIdKey = stringPreferencesKey("default_camera_device_id")
    private val cameraEncodeKey = booleanPreferencesKey("camera_encode_h264")

    val dynamicColor: Flow<Boolean> = context.userPrefs.data.map { prefs ->
        prefs[dynamicKey] ?: true
    }

    val appSettings: Flow<AppSettings> = context.userPrefs.data.map { prefs ->
        AppSettings(
            touchAsMouse = prefs[touchAsMouseKey] ?: true,
            hapticFeedback = prefs[hapticKey] ?: true,
            biometricUnlock = prefs[biometricKey] ?: false,
            autoDisconnectIdle = prefs[autoDisconnectKey] ?: false,
            bandwidthProfile = prefs[bandwidthProfileKey] ?: "Adaptive",
            defaultResolution = prefs[defaultResolutionKey] ?: "Match device",
            keyboardLayout = prefs[keyboardLayoutKey] ?: "US English",
            autoLockVaultMinutes = prefs[autoLockVaultKey] ?: 5,
            renderBackend = prefs[renderBackendKey] ?: RenderBackends.AUTO,
            renderSampling = prefs[renderSamplingKey] ?: RenderSamplingOptions.BILINEAR,
            connectionViewMode = prefs[connectionViewModeKey] ?: ConnectionViewModes.LIST,
            customResolutions = (prefs[customResolutionsKey] ?: emptySet()).sorted(),
            defaultDpiScale = (prefs[defaultDpiScaleKey] ?: 100).coerceIn(DpiScales.MIN, DpiScales.MAX),
            dexDpiScale = (prefs[dexDpiScaleKey] ?: 100).coerceIn(DpiScales.MIN, DpiScales.MAX),
            defaultAudioMode = prefs[defaultAudioModeKey] ?: AudioModes.LOCAL,
            defaultMicrophoneEnabled = prefs[defaultMicrophoneEnabledKey] ?: false,
            defaultAudioQuality = prefs[defaultAudioQualityKey] ?: AudioQualities.DYNAMIC,
            defaultCameraMode = prefs[defaultCameraModeKey] ?: CameraModes.OFF,
            defaultCameraDeviceId = prefs[defaultCameraDeviceIdKey]?.takeIf { it.isNotBlank() },
            cameraEncode = prefs[cameraEncodeKey] ?: true,
        )
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.userPrefs.edit { it[dynamicKey] = enabled }
    }

    suspend fun setTouchAsMouse(value: Boolean) {
        context.userPrefs.edit { it[touchAsMouseKey] = value }
    }

    suspend fun setHapticFeedback(value: Boolean) {
        context.userPrefs.edit { it[hapticKey] = value }
    }

    suspend fun setBiometricUnlock(value: Boolean) {
        context.userPrefs.edit { it[biometricKey] = value }
    }

    suspend fun setAutoDisconnectIdle(value: Boolean) {
        context.userPrefs.edit { it[autoDisconnectKey] = value }
    }

    suspend fun setBandwidthProfile(value: String) {
        context.userPrefs.edit { it[bandwidthProfileKey] = value }
    }

    suspend fun setDefaultResolution(value: String) {
        context.userPrefs.edit { it[defaultResolutionKey] = value }
    }

    suspend fun setKeyboardLayout(value: String) {
        context.userPrefs.edit { it[keyboardLayoutKey] = value }
    }

    suspend fun setAutoLockVaultMinutes(value: Int) {
        context.userPrefs.edit { it[autoLockVaultKey] = value }
    }

    suspend fun setRenderBackend(value: String) {
        context.userPrefs.edit { it[renderBackendKey] = value }
    }

    suspend fun setRenderSampling(value: String) {
        context.userPrefs.edit { it[renderSamplingKey] = value }
    }

    suspend fun setConnectionViewMode(value: String) {
        context.userPrefs.edit { it[connectionViewModeKey] = value }
    }

    suspend fun addCustomResolution(value: String) {
        val normalized = Resolutions.normalize(value) ?: return
        if (Resolutions.BUILT_IN.contains(normalized)) return
        context.userPrefs.edit { prefs ->
            val existing = prefs[customResolutionsKey] ?: emptySet()
            prefs[customResolutionsKey] = existing + normalized
        }
    }

    suspend fun setDefaultDpiScale(value: Int) {
        val clamped = value.coerceIn(DpiScales.MIN, DpiScales.MAX)
        context.userPrefs.edit { it[defaultDpiScaleKey] = clamped }
    }

    suspend fun setDexDpiScale(value: Int) {
        val clamped = value.coerceIn(DpiScales.MIN, DpiScales.MAX)
        context.userPrefs.edit { it[dexDpiScaleKey] = clamped }
    }

    suspend fun setDefaultAudioMode(value: String) {
        if (value !in AudioModes.OPTIONS) return
        context.userPrefs.edit { it[defaultAudioModeKey] = value }
    }

    suspend fun setDefaultMicrophoneEnabled(value: Boolean) {
        context.userPrefs.edit { it[defaultMicrophoneEnabledKey] = value }
    }

    suspend fun setDefaultAudioQuality(value: String) {
        if (value !in AudioQualities.OPTIONS) return
        context.userPrefs.edit { it[defaultAudioQualityKey] = value }
    }

    suspend fun setDefaultCameraMode(value: String) {
        if (value !in CameraModes.OPTIONS) return
        context.userPrefs.edit { it[defaultCameraModeKey] = value }
    }

    suspend fun setDefaultCameraDeviceId(value: String?) {
        context.userPrefs.edit {
            if (value.isNullOrBlank()) it.remove(defaultCameraDeviceIdKey)
            else it[defaultCameraDeviceIdKey] = value
        }
    }

    suspend fun setCameraEncode(value: Boolean) {
        context.userPrefs.edit { it[cameraEncodeKey] = value }
    }

    suspend fun removeCustomResolution(value: String) {
        context.userPrefs.edit { prefs ->
            val existing = prefs[customResolutionsKey] ?: return@edit
            val updated = existing - value
            prefs[customResolutionsKey] = updated
            if (prefs[defaultResolutionKey] == value) {
                prefs[defaultResolutionKey] = Resolutions.MATCH_DEVICE
            }
        }
    }
}
