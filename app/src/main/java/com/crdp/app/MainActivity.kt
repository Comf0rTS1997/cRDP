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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.crdp.core.ui.biometric.BiometricPrompter
import com.crdp.core.ui.biometric.BiometricResult
import com.crdp.core.ui.theme.CrdpTheme
import com.crdp.feature.connections.ConnectionDetailsRoute
import com.crdp.feature.connections.ConnectionDetailsViewModel
import com.crdp.feature.connections.ConnectionEditorRoute
import com.crdp.feature.connections.ConnectionEditorViewModel
import com.crdp.feature.connections.ConnectionListRoute
import com.crdp.feature.connections.ConnectionListViewModel
import com.crdp.feature.connections.ConnectionViewMode
import com.crdp.feature.connections.ConnectionsTwoPaneRoute
import com.crdp.feature.connections.VaultRoute
import com.crdp.app.prefs.CameraModes
import com.crdp.app.prefs.ConnectionViewModes
import com.crdp.app.prefs.RenderBackends
import com.crdp.app.prefs.RenderSamplingOptions
import com.crdp.core.rdp.engine.RenderBackend
import com.crdp.core.rdp.engine.RenderOptions
import com.crdp.core.rdp.engine.SamplingMode
import com.crdp.feature.session.KeyEventHost
import com.crdp.feature.session.SessionRoute
import com.crdp.feature.session.SessionUserSettings
import com.crdp.feature.session.SessionViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

private suspend fun verifyBiometricForProfile(
    mainViewModel: MainViewModel,
    context: android.content.Context,
    profileId: String,
    vaultEncryption: Boolean,
): Boolean {
    val profile = mainViewModel.getProfile(profileId) ?: return true
    // Single global gate: only prompt when (a) vault encryption is enabled AND
    // (b) the profile actually references vault credentials that we'd be unlocking.
    // No vault link → nothing to gate. Vault encryption off → user opted into
    // plaintext storage, so no UI auth check either.
    if (!vaultEncryption) return true
    if (!mainViewModel.referencesVaultCredentials(profile)) return true
    val activity = context as? FragmentActivity ?: return false
    val result = BiometricPrompter.prompt(
        activity = activity,
        title = "Unlock vault",
        subtitle = profile.displayName,
    )
    return result is BiometricResult.Success
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

            CrdpTheme(darkTheme = isSystemInDarkTheme(), dynamicColor = dynamicColor) {
                val navController = rememberNavController()
                val coroutineScope = rememberCoroutineScope()
                val context = LocalContext.current

                fun connectWithBiometric(profileId: String) {
                    coroutineScope.launch {
                        if (verifyBiometricForProfile(
                                mainViewModel, context, profileId,
                                appSettings.vaultEncryption,
                            )
                        ) {
                            navController.navigate("session/${Uri.encode(profileId)}")
                        }
                    }
                }

                fun saveAndConnectWithBiometric(profileId: String) {
                    coroutineScope.launch {
                        if (verifyBiometricForProfile(
                                mainViewModel, context, profileId,
                                appSettings.vaultEncryption,
                            )
                        ) {
                            navController.navigate("session/${Uri.encode(profileId)}") {
                                popUpTo("connections")
                            }
                        }
                    }
                }

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
                                onVaultEncryption = mainViewModel::setVaultEncryption,
                                onAutoDisconnectIdle = mainViewModel::setAutoDisconnectIdle,
                                onDefaultResolution = mainViewModel::setDefaultResolution,
                                onKeyboardLayout = mainViewModel::setKeyboardLayout,
                                onAutoLockVaultMinutes = mainViewModel::setAutoLockVaultMinutes,
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
                                onOpenVault = { navController.navigate("settings/vault") },
                                onOpenAbout = { navController.navigate("settings/about") },
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
                                onSaveAndConnect = ::saveAndConnectWithBiometric,
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
                                settings = SessionUserSettings(
                                    hapticFeedback = appSettings.hapticFeedback,
                                    touchAsMouse = appSettings.touchAsMouse,
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
                                ),
                            )
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
