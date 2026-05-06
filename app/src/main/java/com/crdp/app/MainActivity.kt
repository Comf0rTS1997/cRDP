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
import com.crdp.feature.connections.LicensesScreen
import com.crdp.feature.connections.VaultRoute
import com.crdp.app.prefs.CameraModes
import com.crdp.app.prefs.ConnectionViewModes
import com.crdp.app.prefs.RenderBackends
import com.crdp.app.prefs.RenderSamplingOptions
import com.crdp.core.rdp.engine.RenderBackend
import com.crdp.core.rdp.engine.RenderOptions
import com.crdp.core.rdp.engine.SamplingMode
import com.crdp.feature.session.SessionRoute
import com.crdp.feature.session.SessionUserSettings
import com.crdp.feature.session.SessionViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

private suspend fun verifyBiometricForProfile(
    mainViewModel: MainViewModel,
    context: android.content.Context,
    profileId: String,
): Boolean {
    val profile = mainViewModel.getProfile(profileId) ?: return true
    if (!mainViewModel.requireBiometric(profile)) return true
    val activity = context as? FragmentActivity ?: return false
    val result = BiometricPrompter.prompt(
        activity = activity,
        title = "Unlock connection",
        subtitle = profile.displayName,
    )
    return result is BiometricResult.Success
}

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

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
                        if (verifyBiometricForProfile(mainViewModel, context, profileId)) {
                            navController.navigate("session/${Uri.encode(profileId)}")
                        }
                    }
                }

                fun saveAndConnectWithBiometric(profileId: String) {
                    coroutineScope.launch {
                        if (verifyBiometricForProfile(mainViewModel, context, profileId)) {
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
                                onBiometricUnlock = mainViewModel::setBiometricUnlock,
                                onAutoDisconnectIdle = mainViewModel::setAutoDisconnectIdle,
                                onBandwidthProfile = mainViewModel::setBandwidthProfile,
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
                                onOpenVault = { navController.navigate("settings/vault") },
                                onOpenLicenses = { navController.navigate("settings/licenses") },
                                versionLabel = "Version 0 \u00B7 Build $0",
                                onBack = { navController.popBackStack() },
                            )
                        }
                        composable(
                            route = "settings/licenses",
                            enterTransition = { EnterTransition.None },
                            exitTransition = { ExitTransition.None },
                            popEnterTransition = { EnterTransition.None },
                            popExitTransition = { ExitTransition.None },
                        ) {
                            LicensesScreen(onBack = { navController.popBackStack() })
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
                                bandwidthProfile = appSettings.bandwidthProfile,
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
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}
