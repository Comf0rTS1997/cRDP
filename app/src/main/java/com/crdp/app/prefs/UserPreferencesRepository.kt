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
import com.crdp.core.rdp.model.VaultProtection
import com.crdp.feature.session.AuxKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class AppSettings(
    val touchAsMouse: Boolean = true,
    val hapticFeedback: Boolean = true,
    /**
     * Scroll-direction selector. Default (false) is macOS-style natural
     * scrolling: content tracks the finger / wheel direction. True flips both
     * physical mouse wheel and two-finger touchpad scroll to traditional
     * Windows behavior (finger-down → page-down). Applies uniformly to mouse
     * input and trackpad-mode two-finger pan.
     */
    val reverseScroll: Boolean = false,
    /**
     * Wheel-delta multiplier for physical mouse / external trackpad scroll
     * events. Stored as a percent — 100 = unchanged, 200 = twice as much
     * scroll per wheel detent, 50 = half. See [ScrollSpeeds].
     */
    val mouseWheelSpeed: Int = 100,
    /**
     * Sensitivity for two-finger touchpad scroll, inversely proportional to
     * the finger travel required per emitted wheel detent. 100 = default
     * (~12dp per detent), 200 = half the travel per detent (faster), 50 =
     * twice the travel (slower).
     */
    val touchpadScrollSpeed: Int = 100,
    val autoDisconnectIdle: Boolean = false,
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
    /** Default plain-text clipboard sync for connections that do not override (direct RDP). */
    val defaultClipboardSync: Boolean = true,
    /** Default: expose a virtual printer for sessions that do not override. */
    val defaultPrinterShare: Boolean = false,
    /**
     * Enable RDP NetworkAutoDetect (bandwidth/RTT probes) at connect time.
     * Drives the `/network:auto` vs `/network:lan` argv choice in the engine.
     * Default true: adaptive-GFX-capable servers throttle quality on low
     * bandwidth; others ignore the probes harmlessly.
     */
    val networkAutoDetect: Boolean = true,
    /** Show the remote desktop wallpaper. FreeRDP default is off; opt-in here. */
    val showDesktopBackground: Boolean = false,
    /** Show full window contents while dragging on the remote (vs outline only). */
    val windowContentsWhileDragging: Boolean = false,
    /** Show menu animations on the remote desktop. */
    val menuAnimations: Boolean = false,
    /** Negotiate the GDI glyph cache. Toggling off forces every glyph back as bitmap. */
    val glyphCache: Boolean = true,
    /** GFX encoder preference. See [Encoders]. */
    val preferredEncoder: String = Encoders.AUTO,
    /**
     * Show the "Pointer captured. Double-tap Esc to release." snackbar each
     * time hardware pointer capture engages. Disable once the user knows the
     * gesture and finds the reminder noisy.
     */
    val showCaptureHint: Boolean = true,
    /**
     * Opt-in switch for the accessibility-backed hardware-key filter
     * ([KeyInterceptorService]). Default off — the feature gates on the
     * user explicitly enabling it AND the OS-side service being live, so
     * mouse/keyboard input still works for users who never opt in.
     */
    val enableKeyInterceptor: Boolean = false,
    /** Name advertised to the remote session for the redirected printer. */
    val printerShareName: String = "cRDP",
    /**
     * IDs of aux keys (Esc/Tab/F1-F12/arrows/modifiers/etc.) shown in the row that
     * sits above the soft keyboard inside a session. See [AuxKeys] for the full
     * registry. Empty set means "use defaults" so a fresh install still gets the
     * expected row instead of an empty bar.
     */
    val auxKeyRowKeys: Set<String> = AuxKeys.DEFAULT_ENABLED,
    /**
     * Active protection mode for the credential vault. See [VaultProtection]:
     *  - [VaultProtection.None]: plaintext, no auth gate.
     *  - [VaultProtection.DeviceKey]: AES-256-GCM under an auth-bound Keystore
     *    master key; biometric/device-credential gate enforced cryptographically.
     *  - [VaultProtection.Password]: AES-256-GCM under a PBKDF2-derived key
     *    from a user passphrase, for devices that can't host an auth-bound key.
     *
     * Migrated from the legacy boolean `vault_encryption` pref (true → DeviceKey,
     * false → None) inside [appSettings].
     */
    val vaultProtection: VaultProtection = VaultProtection.DeviceKey,
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
    val OPTIONS = listOf(OFF, FRONT, BACK, EXTERNAL)
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

object Encoders {
    const val AUTO = "Auto"
    const val AVC444 = "H.264 AVC444"
    const val AVC420 = "H.264 AVC420"
    const val PROGRESSIVE = "RemoteFX Progressive"
    const val RFX = "RemoteFX (RFX)"
    val OPTIONS = listOf(AUTO, AVC444, AVC420, PROGRESSIVE, RFX)
}

object RenderSamplingOptions {
    const val NEAREST = "Nearest (sharp text)"
    const val BILINEAR = "Bilinear"
    const val LANCZOS = "Lanczos3 (sharper)"
    val OPTIONS = listOf(NEAREST, BILINEAR, LANCZOS)
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
    const val US_ENGLISH = "US English"
    const val UK_ENGLISH = "UK English"
    const val GERMAN = "German"
    const val FRENCH = "French"
    const val JAPANESE = "Japanese"
    val OPTIONS = listOf(US_ENGLISH, UK_ENGLISH, GERMAN, FRENCH, JAPANESE)

    /**
     * Microsoft LCID for each picker label. Passed to FreeRDP as
     * `/kbd:lang:0x<id>` so the RDP server interprets client scancodes against
     * the intended layout instead of guessing from the server locale.
     */
    fun layoutId(label: String): Int = when (label) {
        US_ENGLISH -> 0x0409
        UK_ENGLISH -> 0x0809
        GERMAN -> 0x0407
        FRENCH -> 0x040C
        JAPANESE -> 0x0411
        else -> 0x0409
    }
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

/**
 * Speed multipliers (as percents) shared by the mouse-wheel and touchpad-scroll
 * settings. 100 is the unmodified baseline; values above accelerate, below
 * decelerate. The set is curated rather than a free slider so users can pick a
 * sensible value without fiddling with a continuous control.
 */
object ScrollSpeeds {
    const val MIN = 25
    const val MAX = 400
    const val DEFAULT = 100
    val OPTIONS = listOf(25, 50, 75, 100, 150, 200, 300, 400)
    fun coerce(value: Int): Int = value.coerceIn(MIN, MAX)
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
    private val reverseScrollKey = booleanPreferencesKey("reverse_scroll")
    private val mouseWheelSpeedKey = intPreferencesKey("mouse_wheel_speed")
    private val touchpadScrollSpeedKey = intPreferencesKey("touchpad_scroll_speed")
    private val autoDisconnectKey = booleanPreferencesKey("auto_disconnect_idle")
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
    private val defaultClipboardSyncKey = booleanPreferencesKey("default_clipboard_sync")
    private val defaultPrinterShareKey = booleanPreferencesKey("default_printer_share")
    private val networkAutoDetectKey = booleanPreferencesKey("network_auto_detect")
    private val showDesktopBackgroundKey = booleanPreferencesKey("show_desktop_background")
    private val windowContentsWhileDraggingKey = booleanPreferencesKey("window_contents_dragging")
    private val menuAnimationsKey = booleanPreferencesKey("menu_animations")
    private val glyphCacheKey = booleanPreferencesKey("glyph_cache")
    private val preferredEncoderKey = stringPreferencesKey("preferred_encoder")
    private val showCaptureHintKey = booleanPreferencesKey("show_capture_hint")
    private val enableKeyInterceptorKey = booleanPreferencesKey("enable_key_interceptor")
    private val printerShareNameKey = stringPreferencesKey("printer_share_name")
    /** Legacy boolean toggle. Read on first launch and migrated to [vaultProtectionKey]. */
    private val vaultEncryptionKey = booleanPreferencesKey("vault_encryption")
    private val vaultProtectionKey = stringPreferencesKey("vault_protection")
    private val auxKeyRowKeysKey = stringSetPreferencesKey("aux_key_row_keys")
    /** Sentinel id stored alongside the user-selected keys to distinguish
     *  "empty selection" from "never written" — without it, unchecking the last
     *  key would look identical to a fresh install and snap back to defaults. */
    private val auxKeyRowSentinelId = "__set__"

    val dynamicColor: Flow<Boolean> = context.userPrefs.data.map { prefs ->
        prefs[dynamicKey] ?: true
    }

    val appSettings: Flow<AppSettings> = context.userPrefs.data.map { prefs ->
        AppSettings(
            touchAsMouse = prefs[touchAsMouseKey] ?: true,
            hapticFeedback = prefs[hapticKey] ?: true,
            reverseScroll = prefs[reverseScrollKey] ?: false,
            mouseWheelSpeed = ScrollSpeeds.coerce(prefs[mouseWheelSpeedKey] ?: ScrollSpeeds.DEFAULT),
            touchpadScrollSpeed = ScrollSpeeds.coerce(prefs[touchpadScrollSpeedKey] ?: ScrollSpeeds.DEFAULT),
            autoDisconnectIdle = prefs[autoDisconnectKey] ?: false,
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
            defaultClipboardSync = prefs[defaultClipboardSyncKey] ?: true,
            defaultPrinterShare = prefs[defaultPrinterShareKey] ?: false,
            networkAutoDetect = prefs[networkAutoDetectKey] ?: true,
            showDesktopBackground = prefs[showDesktopBackgroundKey] ?: false,
            windowContentsWhileDragging = prefs[windowContentsWhileDraggingKey] ?: false,
            menuAnimations = prefs[menuAnimationsKey] ?: false,
            glyphCache = prefs[glyphCacheKey] ?: true,
            preferredEncoder = prefs[preferredEncoderKey]?.takeIf { it in Encoders.OPTIONS } ?: Encoders.AUTO,
            showCaptureHint = prefs[showCaptureHintKey] ?: true,
            enableKeyInterceptor = prefs[enableKeyInterceptorKey] ?: false,
            printerShareName = prefs[printerShareNameKey]?.takeIf { it.isNotBlank() } ?: "cRDP",
            vaultProtection = run {
                val raw = prefs[vaultProtectionKey]
                when (raw) {
                    "none" -> VaultProtection.None
                    "device" -> VaultProtection.DeviceKey
                    "password" -> VaultProtection.Password
                    else -> {
                        // Fall back to the legacy boolean during migration.
                        val legacy = prefs[vaultEncryptionKey]
                        if (legacy == false) VaultProtection.None else VaultProtection.DeviceKey
                    }
                }
            },
            auxKeyRowKeys = run {
                val stored = prefs[auxKeyRowKeysKey]
                if (stored == null) AuxKeys.DEFAULT_ENABLED
                else (stored - auxKeyRowSentinelId).intersect(AuxKeys.BY_ID.keys)
            },
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

    suspend fun setReverseScroll(value: Boolean) {
        context.userPrefs.edit { it[reverseScrollKey] = value }
    }

    suspend fun setMouseWheelSpeed(value: Int) {
        context.userPrefs.edit { it[mouseWheelSpeedKey] = ScrollSpeeds.coerce(value) }
    }

    suspend fun setTouchpadScrollSpeed(value: Int) {
        context.userPrefs.edit { it[touchpadScrollSpeedKey] = ScrollSpeeds.coerce(value) }
    }

    suspend fun setAutoDisconnectIdle(value: Boolean) {
        context.userPrefs.edit { it[autoDisconnectKey] = value }
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

    suspend fun setDefaultClipboardSync(value: Boolean) {
        context.userPrefs.edit { it[defaultClipboardSyncKey] = value }
    }

    suspend fun setDefaultPrinterShare(value: Boolean) {
        context.userPrefs.edit { it[defaultPrinterShareKey] = value }
    }

    suspend fun setNetworkAutoDetect(value: Boolean) {
        context.userPrefs.edit { it[networkAutoDetectKey] = value }
    }

    suspend fun setShowDesktopBackground(value: Boolean) {
        context.userPrefs.edit { it[showDesktopBackgroundKey] = value }
    }

    suspend fun setWindowContentsWhileDragging(value: Boolean) {
        context.userPrefs.edit { it[windowContentsWhileDraggingKey] = value }
    }

    suspend fun setMenuAnimations(value: Boolean) {
        context.userPrefs.edit { it[menuAnimationsKey] = value }
    }

    suspend fun setGlyphCache(value: Boolean) {
        context.userPrefs.edit { it[glyphCacheKey] = value }
    }

    suspend fun setPreferredEncoder(value: String) {
        if (value !in Encoders.OPTIONS) return
        context.userPrefs.edit { it[preferredEncoderKey] = value }
    }

    suspend fun setShowCaptureHint(value: Boolean) {
        context.userPrefs.edit { it[showCaptureHintKey] = value }
    }

    suspend fun setEnableKeyInterceptor(value: Boolean) {
        context.userPrefs.edit { it[enableKeyInterceptorKey] = value }
    }

    /** Trims and falls back to "cRDP" if empty so the server never sees a blank label. */
    suspend fun setPrinterShareName(value: String) {
        val trimmed = value.trim().ifEmpty { "cRDP" }
        context.userPrefs.edit { it[printerShareNameKey] = trimmed }
    }

    suspend fun setVaultProtection(value: VaultProtection) {
        val token = when (value) {
            VaultProtection.None -> "none"
            VaultProtection.DeviceKey -> "device"
            VaultProtection.Password -> "password"
        }
        context.userPrefs.edit { prefs ->
            prefs[vaultProtectionKey] = token
            // Drop the legacy key so the migration fallback above doesn't
            // shadow a user choice if the new key is ever cleared.
            prefs.remove(vaultEncryptionKey)
        }
    }

    suspend fun setAuxKeyRowKey(id: String, enabled: Boolean) {
        if (!AuxKeys.BY_ID.containsKey(id)) return
        context.userPrefs.edit { prefs ->
            val stored = prefs[auxKeyRowKeysKey]
            val current: Set<String> = stored ?: (AuxKeys.DEFAULT_ENABLED + auxKeyRowSentinelId)
            val updated = if (enabled) current + id else current - id
            // Always re-pin the sentinel so an empty selection still reads back as
            // "user wrote {}" rather than reverting to defaults.
            prefs[auxKeyRowKeysKey] = updated + auxKeyRowSentinelId
        }
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
