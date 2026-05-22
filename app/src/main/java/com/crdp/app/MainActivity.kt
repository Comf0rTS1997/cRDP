package com.crdp.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.compose.runtime.CompositionLocalProvider
import com.crdp.core.rdp.model.VaultProtection
import com.crdp.core.rdp.repository.UnlockOutcome
import com.crdp.core.ui.vault.LocalVaultGatekeeper
import com.crdp.core.ui.vault.VaultGatekeeper
import kotlinx.coroutines.CompletableDeferred
import com.crdp.core.ui.theme.CrdpTheme
import com.crdp.feature.connections.ConnectionDetailsRoute
import com.crdp.feature.connections.ConnectionDetailsViewModel
import com.crdp.feature.connections.ConnectionEditorRoute
import com.crdp.feature.connections.ConnectionEditorViewModel
import com.crdp.feature.connections.NewProfileDefaults
import com.crdp.feature.connections.ConnectionListRoute
import com.crdp.feature.connections.ConnectionListViewModel
import com.crdp.feature.connections.ConnectionViewMode
import com.crdp.feature.connections.ConnectionsTwoPaneRoute
import com.crdp.feature.connections.VaultRoute
import com.crdp.app.prefs.AutoLockVault
import com.crdp.app.prefs.CameraModes
import com.crdp.app.prefs.ConnectionViewModes
import com.crdp.app.prefs.KeyboardLayouts
import com.crdp.app.prefs.RenderBackends
import com.crdp.app.prefs.RenderSamplingOptions
import com.crdp.app.prefs.Resolutions
import com.crdp.core.rdp.engine.RenderBackend
import com.crdp.core.rdp.engine.RenderOptions
import com.crdp.core.rdp.engine.SamplingMode
import com.crdp.feature.session.KeyEventHost
import com.crdp.feature.session.SessionRoute
import com.crdp.feature.session.SessionUserSettings
import com.crdp.feature.session.SessionViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Translate the app's "Default resolution" string ("Match device" / "WxH") into
 * the editor's initial autoResolution + width/height for brand-new profiles.
 * Unknown / malformed values fall back to autoResolution=false at 1280x720,
 * matching [com.crdp.feature.connections.NewProfileDefaults]'s own defaults.
 */
private fun resolveNewProfileDefaults(setting: String): NewProfileDefaults {
    if (setting == Resolutions.MATCH_DEVICE) {
        return NewProfileDefaults(autoResolution = true, width = 1280, height = 720)
    }
    val normalized = Resolutions.normalize(setting) ?: return NewProfileDefaults()
    val parts = normalized.split('x')
    val w = parts.getOrNull(0)?.toIntOrNull() ?: return NewProfileDefaults()
    val h = parts.getOrNull(1)?.toIntOrNull() ?: return NewProfileDefaults()
    return NewProfileDefaults(autoResolution = false, width = w, height = h)
}

/**
 * Connect-time vault gate. Skips the prompt for profiles that don't reference
 * a vault entry (nothing to decrypt) and otherwise hands off to the
 * centralized [VaultGatekeeper] so the auth UX matches every other place that
 * touches the vault (editor credential save, vault settings, future flows).
 */
private suspend fun unlockVaultForProfile(
    mainViewModel: MainViewModel,
    gatekeeper: VaultGatekeeper,
    profileId: String,
    notifyPromptOnPhone: Boolean,
): Boolean {
    val profile = mainViewModel.getProfile(profileId) ?: return true
    if (!mainViewModel.referencesVaultCredentials(profile)) return true
    return gatekeeper.ensureUnlocked(
        title = "Unlock vault",
        subtitle = profile.displayName,
        notifyPromptOnPhone = notifyPromptOnPhone,
    )
}

@AndroidEntryPoint
class MainActivity : FragmentActivity(), KeyEventHost {

    /**
     * Per-Activity key sink the session screen registers while connected.
     * Intercepts `KeyEvent`s before the IME and View focus system get a chance
     * to swallow them — Alt+F4 in particular never reaches a Compose
     * `onPreviewKeyEvent` handler when an EditText steals focus, but does land
     * here. Return `true` to consume.
     *
     * Mirrors the dispatchKeyEvent override pattern used by termux-x11's
     * `MainActivity` (LorieView.dispatchKeyEvent). The hook is set/cleared in
     * `SessionScreen`'s pointer-capture `DisposableEffect`.
     */
    @Volatile private var keyEventHook: ((android.view.KeyEvent) -> Boolean)? = null

    /**
     * Idempotency guard for [SamsungDexUtils.dexMetaKeyCapture]. Compose
     * recomposes the session screen frequently and each one calls
     * setKeyEventHook(non-null) again; without this we'd churn the Samsung
     * meta-key capture state on every recomposition, which the input
     * dispatcher seems to react to by occasionally dropping keys.
     */
    private var dexCaptureEnabled = false

    override fun setKeyEventHook(hook: ((android.view.KeyEvent) -> Boolean)?) {
        keyEventHook = hook
        // Notify the accessibility filter that its enable/disable state may
        // have flipped (session connected/disconnected → intercept on/off).
        KeyInterceptorService.recheck()
        // DeX-specific: AccessibilityService key filtering does NOT cover
        // Samsung's meta-key shortcuts (Win, Alt+F4, Alt+Tab). The Samsung
        // private API SemWindowManager.requestMetaKeyEvent is the only path
        // that routes those to the Activity. Toggle in lockstep with the
        // session hook, but only on state change (see [dexCaptureEnabled]).
        val shouldCapture = hook != null
        if (shouldCapture != dexCaptureEnabled) {
            SamsungDexUtils.dexMetaKeyCapture(this, shouldCapture)
            dexCaptureEnabled = shouldCapture
        }
    }

    /**
     * True when the AccessibilityService should be filtering keys — i.e.
     * a session is live AND the activity is foreground. Mirrors termux-x11's
     * `MainActivity.shouldInterceptKeys` semantics.
     */
    fun shouldInterceptKeys(): Boolean = keyEventHook != null

    /**
     * Called by [KeyInterceptorService.onKeyEvent] with events that arrive
     * BEFORE the WindowManager's shortcut router (Alt+F4 etc.). Forwards to
     * the registered session hook; returns `true` if consumed so the system
     * drops the event entirely.
     */
    fun dispatchInterceptedKey(event: android.view.KeyEvent): Boolean {
        val hook = keyEventHook ?: return false
        return hook(event)
    }

    override fun onResume() {
        super.onResume()
        activeInstance = this
        KeyInterceptorService.recheck()
    }

    override fun onPause() {
        // Release the filter when we lose foreground so other apps get normal
        // key dispatch.
        if (activeInstance === this) activeInstance = null
        KeyInterceptorService.recheck()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        KeyInterceptorService.recheck()
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val hook = keyEventHook
        if (hook != null && hook(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /**
     * Window-level shortcut dispatch. Fires EARLIER than `dispatchKeyEvent` for
     * keys with a modifier (Alt/Ctrl/Meta) — this is where Samsung DeX's
     * "Alt+F4 closes the foreground app" lives, and where stock Android routes
     * Alt+Tab / Win+L. By consuming here, we keep the shortcut from reaching
     * the WindowManager's global shortcut router.
     */
    override fun dispatchKeyShortcutEvent(event: android.view.KeyEvent): Boolean {
        val hook = keyEventHook
        if (hook != null && hook(event)) return true
        return super.dispatchKeyShortcutEvent(event)
    }

    /**
     * Stock Activity.onKeyDown handles Back / Menu / Volume / Power. We
     * intentionally never let it run for any session-forwarded key — but those
     * are already consumed in dispatchKeyEvent above. This override exists
     * solely as a hard backstop if some code path manages to reach onKeyDown
     * without dispatchKeyEvent first (e.g., key prefilters added by Samsung).
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val hook = keyEventHook
        if (hook != null && hook(event)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val hook = keyEventHook
        if (hook != null && hook(event)) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val mainViewModel: MainViewModel = hiltViewModel()
            val dynamicColor by mainViewModel.dynamicColor.collectAsStateWithLifecycle()
            val appSettings by mainViewModel.appSettings.collectAsStateWithLifecycle()

            // Re-engage the vault gate whenever the app leaves the foreground, unless
            // the user picked "Never". The in-Compose timer handles in-app idling;
            // this covers the case where the user backgrounds the app for longer than
            // the auto-lock window.
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner, appSettings.autoLockVaultMinutes) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_STOP &&
                        appSettings.autoLockVaultMinutes != AutoLockVault.NEVER) {
                        mainViewModel.lockVault()
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            CrdpTheme(darkTheme = isSystemInDarkTheme(), dynamicColor = dynamicColor) {
                val navController = rememberNavController()
                val coroutineScope = rememberCoroutineScope()
                val context = LocalContext.current
                val expandedWindow = isExpandedWindow()

                // Compose-state-backed password prompt: when a connect path needs
                // a vault password and there's no cached key, it parks a deferred
                // here and awaits user input. The dialog below resolves the
                // deferred with the typed password (or null on cancel).
                var pendingPasswordRequest by remember {
                    mutableStateOf<CompletableDeferred<CharArray?>?>(null)
                }
                val requestPassword: suspend () -> CharArray? = {
                    val deferred = CompletableDeferred<CharArray?>()
                    pendingPasswordRequest = deferred
                    val result = deferred.await()
                    pendingPasswordRequest = null
                    result
                }

                // Single source of truth for "ask the user to unlock the vault".
                // Constructed inside setContent so it can close over the activity,
                // the password-dialog deferred, and the MainViewModel. Provided to
                // descendants via [LocalVaultGatekeeper] so feature modules don't
                // re-implement BiometricPrompt / password dialog wiring.
                val vaultGatekeeper = remember(mainViewModel) {
                    AppVaultGatekeeper(
                        activity = this@MainActivity,
                        getProtection = { mainViewModel.appSettings.value.vaultProtection },
                        isUnlocked = {
                            mainViewModel.isVaultUnlocked(
                                mainViewModel.appSettings.value.autoLockVaultMinutes,
                            )
                        },
                        completeDeviceKeyUnlock = { mainViewModel.completeDeviceKeyUnlock() },
                        unlockWithPassword = { pw -> mainViewModel.unlockWithPassword(pw) },
                        requestPassword = requestPassword,
                    )
                }

                fun connectWithBiometric(profileId: String) {
                    coroutineScope.launch {
                        if (unlockVaultForProfile(
                                mainViewModel = mainViewModel,
                                gatekeeper = vaultGatekeeper,
                                profileId = profileId,
                                notifyPromptOnPhone = expandedWindow,
                            )
                        ) {
                            navController.navigate("session/${Uri.encode(profileId)}")
                        }
                    }
                }

                val activePasswordRequest = pendingPasswordRequest
                if (activePasswordRequest != null) {
                    ConnectVaultPasswordDialog(
                        onCancel = { activePasswordRequest.complete(null) },
                        onConfirm = { pw -> activePasswordRequest.complete(pw) },
                    )
                }

                CompositionLocalProvider(LocalVaultGatekeeper provides vaultGatekeeper) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background,
                    contentWindowInsets = WindowInsets(0),
                ) { _ ->
                  AdaptiveAppShell(navController = navController) {
                    NavHost(
                        navController = navController,
                        startDestination = "connections",
                        modifier = Modifier.fillMaxSize(),
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None },
                    ) {
                        composable(
                            route = "connections",
                            enterTransition = { EnterTransition.None },
                            exitTransition = { ExitTransition.None },
                            popEnterTransition = { EnterTransition.None },
                            popExitTransition = { ExitTransition.None },
                        ) {
                            val vm: ConnectionListViewModel = hiltViewModel()
                            val currentViewMode = when (appSettings.connectionViewMode) {
                                ConnectionViewModes.GRID -> ConnectionViewMode.Grid
                                else -> ConnectionViewMode.List
                            }
                            if (isExpandedWindow()) {
                                ConnectionsTwoPaneRoute(
                                    viewModel = vm,
                                    viewMode = currentViewMode,
                                    onOpenSession = ::connectWithBiometric,
                                    onEditConnection = { id ->
                                        navController.navigate("editor/${Uri.encode(id)}")
                                    },
                                )
                            } else {
                                ConnectionListRoute(
                                    viewModel = vm,
                                    onAddConnection = { navController.navigate("editor/new") },
                                    onEditConnection = { id -> navController.navigate("editor/${Uri.encode(id)}") },
                                    onOpenSession = ::connectWithBiometric,
                                    onOpenDetails = { id -> navController.navigate("details/${Uri.encode(id)}") },
                                    onOpenSettings = { navController.navigate("settings") },
                                    viewMode = currentViewMode,
                                    onViewModeChange = { mode ->
                                        mainViewModel.setConnectionViewMode(
                                            when (mode) {
                                                ConnectionViewMode.Grid -> ConnectionViewModes.GRID
                                                ConnectionViewMode.List -> ConnectionViewModes.LIST
                                            },
                                        )
                                    },
                                )
                            }
                        }
                        composable(
                            route = "settings",
                            enterTransition = {
                                slideInHorizontally(
                                    animationSpec = tween(durationMillis = 300),
                                    initialOffsetX = { it },
                                ) + fadeIn(animationSpec = tween(durationMillis = 300))
                            },
                            exitTransition = {
                                slideOutHorizontally(
                                    animationSpec = tween(durationMillis = 300),
                                    targetOffsetX = { -it / 4 },
                                ) + fadeOut(animationSpec = tween(durationMillis = 300))
                            },
                            popEnterTransition = {
                                slideInHorizontally(
                                    animationSpec = tween(durationMillis = 300),
                                    initialOffsetX = { -it / 4 },
                                ) + fadeIn(animationSpec = tween(durationMillis = 300))
                            },
                            popExitTransition = {
                                slideOutHorizontally(
                                    animationSpec = tween(durationMillis = 300),
                                    targetOffsetX = { it },
                                ) + fadeOut(animationSpec = tween(durationMillis = 300))
                            },
                        ) {
                            SettingsScreen(
                                dynamicColor = dynamicColor,
                                onDynamicColor = mainViewModel::setDynamicColor,
                                appSettings = appSettings,
                                onTouchAsMouse = mainViewModel::setTouchAsMouse,
                                onHapticFeedback = mainViewModel::setHapticFeedback,
                                onOpenKeyboardRow = { navController.navigate("settings/keyboard-row") },
                                onOpenMouseSettings = { navController.navigate("settings/mouse") },
                                onAutoDisconnectIdle = mainViewModel::setAutoDisconnectIdle,
                                onDefaultResolution = mainViewModel::setDefaultResolution,
                                onKeyboardLayout = mainViewModel::setKeyboardLayout,
                                onRenderBackend = mainViewModel::setRenderBackend,
                                onRenderSampling = mainViewModel::setRenderSampling,
                                onAddCustomResolution = mainViewModel::addCustomResolution,
                                onRemoveCustomResolution = mainViewModel::removeCustomResolution,
                                onDefaultDpiScale = mainViewModel::setDefaultDpiScale,
                                onDexDpiScale = mainViewModel::setDexDpiScale,
                                onDefaultAudioMode = mainViewModel::setDefaultAudioMode,
                                onDefaultMicrophoneEnabled = mainViewModel::setDefaultMicrophoneEnabled,
                                onDefaultAudioQuality = mainViewModel::setDefaultAudioQuality,
                                onDefaultCameraMode = mainViewModel::setDefaultCameraMode,
                                onDefaultClipboardSync = mainViewModel::setDefaultClipboardSync,
                                onDefaultPrinterShare = mainViewModel::setDefaultPrinterShare,
                                onNetworkAutoDetect = mainViewModel::setNetworkAutoDetect,
                                onShowDesktopBackground = mainViewModel::setShowDesktopBackground,
                                onWindowContentsWhileDragging = mainViewModel::setWindowContentsWhileDragging,
                                onMenuAnimations = mainViewModel::setMenuAnimations,
                                onGlyphCache = mainViewModel::setGlyphCache,
                                onPreferredEncoder = mainViewModel::setPreferredEncoder,
                                onShowCaptureHint = mainViewModel::setShowCaptureHint,
                                onEnableKeyInterceptor = mainViewModel::setEnableKeyInterceptor,
                                onOpenVault = { navController.navigate("settings/vault") },
                                onOpenPermissions = { navController.navigate("settings/permissions") },
                                onOpenAbout = { navController.navigate("settings/about") },
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable(
                            route = "settings/permissions",
                            enterTransition = { EnterTransition.None },
                            exitTransition = { ExitTransition.None },
                            popEnterTransition = { EnterTransition.None },
                            popExitTransition = { ExitTransition.None },
                        ) {
                            PermissionsSettingsScreen(
                                appSettings = appSettings,
                                onEnableKeyInterceptor = mainViewModel::setEnableKeyInterceptor,
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable(
                            route = "settings/keyboard-row",
                            enterTransition = { EnterTransition.None },
                            exitTransition = { ExitTransition.None },
                            popEnterTransition = { EnterTransition.None },
                            popExitTransition = { ExitTransition.None },
                        ) {
                            KeyboardRowSettingsScreen(
                                enabled = appSettings.auxKeyRowKeys,
                                onToggle = mainViewModel::setAuxKeyRowKey,
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable(
                            route = "settings/mouse",
                            enterTransition = { EnterTransition.None },
                            exitTransition = { ExitTransition.None },
                            popEnterTransition = { EnterTransition.None },
                            popExitTransition = { ExitTransition.None },
                        ) {
                            MouseSettingsScreen(
                                appSettings = appSettings,
                                onReverseScroll = mainViewModel::setReverseScroll,
                                onMouseWheelSpeed = mainViewModel::setMouseWheelSpeed,
                                onTouchpadScrollSpeed = mainViewModel::setTouchpadScrollSpeed,
                                onMousePointerSpeed = mainViewModel::setMousePointerSpeed,
                                onTouchpadPointerSpeed = mainViewModel::setTouchpadPointerSpeed,
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable(
                            route = "settings/about",
                            enterTransition = { EnterTransition.None },
                            exitTransition = { ExitTransition.None },
                            popEnterTransition = { EnterTransition.None },
                            popExitTransition = { ExitTransition.None },
                        ) {
                            AboutScreen(
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable(
                            route = "settings/vault",
                            enterTransition = { EnterTransition.None },
                            exitTransition = { ExitTransition.None },
                            popEnterTransition = { EnterTransition.None },
                            popExitTransition = { ExitTransition.None },
                        ) {
                            val deviceKeySupported by mainViewModel.deviceKeySupported.collectAsStateWithLifecycle()
                            val vaultProtectionResult by mainViewModel.vaultProtectionResult.collectAsStateWithLifecycle()
                            VaultSettingsScreen(
                                appSettings = appSettings,
                                deviceKeySupported = deviceKeySupported,
                                vaultProtectionResult = vaultProtectionResult,
                                onVaultProtectionChange = { target, pw ->
                                    mainViewModel.requestVaultProtection(target, pw)
                                },
                                onAutoLockVaultMinutes = mainViewModel::setAutoLockVaultMinutes,
                                onOpenCredentials = { navController.navigate("settings/vault/credentials") },
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable(
                            route = "settings/vault/credentials",
                            enterTransition = { EnterTransition.None },
                            exitTransition = { ExitTransition.None },
                            popEnterTransition = { EnterTransition.None },
                            popExitTransition = { ExitTransition.None },
                        ) {
                            VaultGate(appSettings = appSettings, mainViewModel = mainViewModel) {
                                VaultRoute(onBack = { navController.popBackStack() })
                            }
                        }
                        composable(
                            route = "details/{profileId}",
                            arguments = listOf(navArgument("profileId") { type = NavType.StringType }),
                            enterTransition = { EnterTransition.None },
                            exitTransition = { ExitTransition.None },
                            popEnterTransition = { EnterTransition.None },
                            popExitTransition = { ExitTransition.None },
                        ) {
                            val vm: ConnectionDetailsViewModel = hiltViewModel()
                            ConnectionDetailsRoute(
                                viewModel = vm,
                                onBack = { navController.popBackStack() },
                                onConnect = ::connectWithBiometric,
                                onEdit = { id -> navController.navigate("editor/${Uri.encode(id)}") },
                            )
                        }
                        composable(
                            route = "editor/{profileId}",
                            arguments = listOf(navArgument("profileId") { type = NavType.StringType }),
                            enterTransition = { EnterTransition.None },
                            exitTransition = { ExitTransition.None },
                            popEnterTransition = { EnterTransition.None },
                            popExitTransition = { ExitTransition.None },
                        ) {
                            val vm: ConnectionEditorViewModel = hiltViewModel()
                            ConnectionEditorRoute(
                                viewModel = vm,
                                onBack = { navController.popBackStack() },
                                onSaved = { navController.popBackStack() },
                                newProfileDefaults = resolveNewProfileDefaults(appSettings.defaultResolution),
                            )
                        }
                        composable(
                            route = "session/{profileId}",
                            arguments = listOf(navArgument("profileId") { type = NavType.StringType }),
                            enterTransition = { EnterTransition.None },
                            exitTransition = { ExitTransition.None },
                            popEnterTransition = { EnterTransition.None },
                            popExitTransition = { ExitTransition.None },
                        ) {
                            val vm: SessionViewModel = hiltViewModel()
                            SessionRoute(
                                viewModel = vm,
                                onBack = { navController.popBackStack() },
                                onTouchAsMouseChanged = mainViewModel::setTouchAsMouse,
                                settings = SessionUserSettings(
                                    hapticFeedback = appSettings.hapticFeedback,
                                    touchAsMouse = appSettings.touchAsMouse,
                                    reverseScroll = appSettings.reverseScroll,
                                    mouseWheelSpeedPercent = appSettings.mouseWheelSpeed,
                                    touchpadScrollSpeedPercent = appSettings.touchpadScrollSpeed,
                                    mousePointerSpeedPercent = appSettings.mousePointerSpeed,
                                    touchpadPointerSpeedPercent = appSettings.touchpadPointerSpeed,
                                    autoDisconnectIdle = appSettings.autoDisconnectIdle,
                                    renderOptions = RenderOptions(
                                        backend = when (appSettings.renderBackend) {
                                            RenderBackends.HWUI -> RenderBackend.HwuiCanvas
                                            RenderBackends.GLES -> RenderBackend.Gles
                                            else -> RenderBackend.Auto
                                        },
                                        sampling = when (appSettings.renderSampling) {
                                            RenderSamplingOptions.NEAREST -> SamplingMode.Nearest
                                            RenderSamplingOptions.LANCZOS -> SamplingMode.Lanczos3
                                            else -> SamplingMode.Bilinear
                                        },
                                    ),
                                    defaultDpiScale = appSettings.defaultDpiScale,
                                    dexDpiScale = appSettings.dexDpiScale,
                                    defaultAudioMode = appSettings.defaultAudioMode,
                                    defaultMicrophoneEnabled = appSettings.defaultMicrophoneEnabled,
                                    defaultAudioQuality = appSettings.defaultAudioQuality,
                                    defaultCameraMode = when (appSettings.defaultCameraMode) {
                                        CameraModes.OFF -> com.crdp.core.rdp.model.CameraMode.Disabled
                                        CameraModes.FRONT -> com.crdp.core.rdp.model.CameraMode.BuiltInFront
                                        CameraModes.BACK -> com.crdp.core.rdp.model.CameraMode.BuiltInBack
                                        CameraModes.EXTERNAL -> com.crdp.core.rdp.model.CameraMode.External
                                        CameraModes.SPECIFIC -> com.crdp.core.rdp.model.CameraMode.Specific
                                        else -> com.crdp.core.rdp.model.CameraMode.Disabled
                                    },
                                    defaultCameraDeviceId = appSettings.defaultCameraDeviceId,
                                    cameraEncode = appSettings.cameraEncode,
                                    defaultClipboardSync = appSettings.defaultClipboardSync,
                                    defaultPrinterShare = appSettings.defaultPrinterShare,
                                    keyboardLayoutId = KeyboardLayouts.layoutId(appSettings.keyboardLayout),
                                    auxKeyRowKeys = appSettings.auxKeyRowKeys,
                                    networkAutoDetect = appSettings.networkAutoDetect,
                                    showDesktopBackground = appSettings.showDesktopBackground,
                                    windowContentsWhileDragging = appSettings.windowContentsWhileDragging,
                                    menuAnimations = appSettings.menuAnimations,
                                    glyphCache = appSettings.glyphCache,
                                    preferredEncoder = appSettings.preferredEncoder,
                                    showCaptureHint = appSettings.showCaptureHint,
                                    enableKeyInterceptor = appSettings.enableKeyInterceptor,
                                ),
                            )
                        }
                    }
                  }
                }
                }
            }
        }
    }

    companion object {
        /**
         * Set in onResume / cleared in onPause. `KeyInterceptorService` reads
         * this to know which (if any) MainActivity should currently receive
         * filtered key events. `@Volatile` so the binder thread sees the
         * latest value without a memory barrier on every onKeyEvent.
         */
        @JvmStatic
        @Volatile var activeInstance: MainActivity? = null
            private set
    }
}

/**
 * Compact password dialog used at connect time when the vault is in
 * [VaultProtection.Password] mode and no derived key is cached yet. Returns
 * the typed password to the caller via [onConfirm]; the caller is responsible
 * for forwarding it to the repo and zeroing the array.
 */
@Composable
private fun ConnectVaultPasswordDialog(
    onCancel: () -> Unit,
    onConfirm: (CharArray) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onCancel,
        title = { androidx.compose.material3.Text("Unlock vault") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                androidx.compose.material3.Text(
                    text = "Enter your vault password to load saved credentials for this connection.",
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { androidx.compose.material3.Text("Password") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                enabled = password.isNotEmpty(),
                onClick = {
                    val out = password.toCharArray()
                    password = ""
                    onConfirm(out)
                },
            ) { androidx.compose.material3.Text("Unlock") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onCancel) {
                androidx.compose.material3.Text("Cancel")
            }
        },
    )
}
