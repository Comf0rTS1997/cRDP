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
/**
 * Enum (not a sealed class of `data object`s) so R8 release minification can't
 * strip the per-entry INSTANCE singletons — that crashed the Permissions
 * screen with "parameter permission is null" when the companion's `entries`
 * list materialized with null members. Enum entries are statically reified
 * by the JVM and survive any R8 pass.
 */
enum class AppPermission(
    val manifestName: String?,
    val featureLabel: String,
    val featureDescription: String,
    val minSdk: Int = 1,
) {
    Notifications(
        manifestName = Manifest.permission.POST_NOTIFICATIONS,
        featureLabel = "Notifications",
        featureDescription = "Used when printer share is enabled, to show a notification when a remote print job is spooled.",
        minSdk = Build.VERSION_CODES.TIRAMISU,
    ),

    Microphone(
        manifestName = Manifest.permission.RECORD_AUDIO,
        featureLabel = "Microphone",
        featureDescription = "Used when a session has microphone redirection enabled.",
    ),

    Camera(
        manifestName = Manifest.permission.CAMERA,
        featureLabel = "Camera",
        featureDescription = "Used when a session has camera redirection enabled.",
    ),

    KeyInterceptor(
        manifestName = null,
        featureLabel = "Hardware-key capture",
        featureDescription =
            "Lets cRDP intercept system shortcuts like Alt+F4, Win+L and Alt+Tab " +
                "while a session is active, so they reach the remote desktop instead " +
                "of Android. Backed by an Accessibility service that only filters key " +
                "events — it does not observe screen content.",
    );

    companion object {
        val ALL: List<AppPermission> = entries.toList()
    }
}

object AppPermissions {

    /**
     * Class name of the accessibility-backed key interceptor. The full
     * ComponentName is built per-context using [Context.getPackageName] so
     * .debug / .staging variants resolve to their own service registration
     * instead of looking for the release package.
     */
    private const val KEY_INTERCEPTOR_SERVICE_CLASS =
        "com.crdp.app.KeyInterceptorService"

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
        val target = ComponentName(context.packageName, KEY_INTERCEPTOR_SERVICE_CLASS)
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
