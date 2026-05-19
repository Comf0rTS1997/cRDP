package com.crdp.app.permissions

import android.Manifest
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.core.content.ContextCompat

enum class PermissionStatus { Granted, Denied, NotApplicable }

/**
 * Single source of truth for every permission cRDP needs at runtime. Each
 * entry pairs the manifest name with a user-facing label and the feature it
 * gates, so the Settings → Permissions sub-page can render a uniform row and
 * the request code stays out of the surfaces that consume the feature.
 *
 * The accessibility-backed [KeyInterceptor] is special-cased: it has no
 * runtime-dialog permission, only the OS Accessibility settings page. Its
 * [manifestName] is `null` and [AppPermissions.status] resolves it via
 * [AppPermissions.isAccessibilityServiceEnabled].
 */
sealed class AppPermission(
    val manifestName: String?,
    val featureLabel: String,
    val featureDescription: String,
    val minSdk: Int = 1,
) {
    data object Notifications : AppPermission(
        manifestName = Manifest.permission.POST_NOTIFICATIONS,
        featureLabel = "Notifications",
        featureDescription = "Used when printer share is enabled, to show a notification when a remote print job is spooled.",
        minSdk = Build.VERSION_CODES.TIRAMISU,
    )

    data object Microphone : AppPermission(
        manifestName = Manifest.permission.RECORD_AUDIO,
        featureLabel = "Microphone",
        featureDescription = "Used when a session has microphone redirection enabled.",
    )

    data object Camera : AppPermission(
        manifestName = Manifest.permission.CAMERA,
        featureLabel = "Camera",
        featureDescription = "Used when a session has camera redirection enabled.",
    )

    data object KeyInterceptor : AppPermission(
        manifestName = null,
        featureLabel = "Hardware-key capture",
        featureDescription =
            "Lets cRDP intercept system shortcuts like Alt+F4, Win+L and Alt+Tab " +
                "while a session is active, so they reach the remote desktop instead " +
                "of Android. Backed by an Accessibility service that only filters key " +
                "events — it does not observe screen content.",
    )

    companion object {
        val ALL: List<AppPermission> = listOf(Notifications, Microphone, Camera, KeyInterceptor)
    }
}

object AppPermissions {

    /**
     * SERVICE_ID matches the one used inside KeyInterceptorService for the
     * Settings.Secure auto-enable path. Kept in sync manually because that
     * file lives in the same module and there is no shared constant.
     */
    private const val KEY_INTERCEPTOR_SERVICE_ID =
        "com.crdp.android/com.crdp.app.KeyInterceptorService"

    fun status(context: Context, permission: AppPermission): PermissionStatus {
        if (Build.VERSION.SDK_INT < permission.minSdk) return PermissionStatus.NotApplicable
        return when (permission) {
            AppPermission.KeyInterceptor ->
                if (isAccessibilityServiceEnabled(context)) PermissionStatus.Granted
                else PermissionStatus.Denied
            else -> {
                val name = permission.manifestName ?: return PermissionStatus.NotApplicable
                if (ContextCompat.checkSelfPermission(context, name) ==
                    PackageManager.PERMISSION_GRANTED
                ) PermissionStatus.Granted else PermissionStatus.Denied
            }
        }
    }

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        if (enabled.isEmpty()) return false
        // ENABLED_ACCESSIBILITY_SERVICES is a colon-separated list of
        // ComponentName flattened strings. Compare structurally so a future
        // rename of the service class still matches by ComponentName equality.
        val target = ComponentName.unflattenFromString(KEY_INTERCEPTOR_SERVICE_ID) ?: return false
        return enabled.split(':').any {
            ComponentName.unflattenFromString(it) == target
        }
    }

    fun openAppDetailsSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }
}

/**
 * Compose helper that returns an idempotent launch function for [permission].
 * Returns a no-op for permissions that have no runtime dialog
 * ([AppPermission.KeyInterceptor]) or are not applicable on the current API
 * level, so call sites do not need to branch.
 */
@Composable
fun rememberPermissionLauncher(
    permission: AppPermission,
    onResult: (granted: Boolean) -> Unit = {},
): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> onResult(granted) }
    return {
        val name = permission.manifestName
        if (name != null && Build.VERSION.SDK_INT >= permission.minSdk) {
            launcher.launch(name)
        }
    }
}
