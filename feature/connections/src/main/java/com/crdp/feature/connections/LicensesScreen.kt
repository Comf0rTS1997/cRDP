package com.crdp.feature.connections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class LicenseEntry(
    val name: String,
    val version: String,
    val license: String,
    val url: String,
    val kind: LicenseKind,
    val notice: String? = null,
)

private val LICENSES = listOf(
    // --- Bundled native components (statically/dynamically linked into the APK) ---
    LicenseEntry(
        "FreeRDP",
        "3.9.0",
        "Apache 2.0",
        "https://github.com/FreeRDP/FreeRDP",
        LicenseKind.APACHE_2_0,
        notice = "Copyright © FreeRDP Project and contributors. Shipped as native libraries (libfreerdp3.so, libfreerdp-client3.so, libwinpr3.so, libfreerdp-android.so) built from upstream sources.",
    ),
    LicenseEntry(
        "OpenSSL",
        "3.3.1",
        "Apache 2.0",
        "https://github.com/openssl/openssl",
        LicenseKind.APACHE_2_0,
        notice = "Copyright © The OpenSSL Project Authors. Statically linked into libfreerdp3.so for TLS / cryptography.",
    ),
    LicenseEntry(
        "cJSON",
        "1.7.18",
        "MIT",
        "https://github.com/DaveGamble/cJSON",
        LicenseKind.MIT_CJSON,
        notice = "Pulled in transitively by FreeRDP for OAuth / Azure AD JSON parsing.",
    ),

    // --- Jetpack / Compose ---
    LicenseEntry(
        "Jetpack Compose (BOM)",
        "2024.12.01",
        "Apache 2.0",
        "https://developer.android.com/jetpack/compose",
        LicenseKind.APACHE_2_0,
    ),
    LicenseEntry(
        "Material 3",
        "1.3.1",
        "Apache 2.0",
        "https://developer.android.com/jetpack/androidx/releases/compose-material3",
        LicenseKind.APACHE_2_0,
    ),
    LicenseEntry(
        "Material 3 Window Size Class",
        "1.3.1",
        "Apache 2.0",
        "https://developer.android.com/jetpack/androidx/releases/compose-material3",
        LicenseKind.APACHE_2_0,
    ),
    LicenseEntry(
        "AndroidX Core KTX",
        "1.15.0",
        "Apache 2.0",
        "https://developer.android.com/jetpack/androidx/releases/core",
        LicenseKind.APACHE_2_0,
    ),
    LicenseEntry(
        "AndroidX Activity Compose",
        "1.9.3",
        "Apache 2.0",
        "https://developer.android.com/jetpack/androidx/releases/activity",
        LicenseKind.APACHE_2_0,
    ),
    LicenseEntry(
        "AndroidX Lifecycle",
        "2.8.7",
        "Apache 2.0",
        "https://developer.android.com/jetpack/androidx/releases/lifecycle",
        LicenseKind.APACHE_2_0,
    ),
    LicenseEntry(
        "AndroidX Navigation Compose",
        "2.8.4",
        "Apache 2.0",
        "https://developer.android.com/jetpack/androidx/releases/navigation",
        LicenseKind.APACHE_2_0,
    ),
    LicenseEntry(
        "AndroidX DataStore Preferences",
        "1.1.1",
        "Apache 2.0",
        "https://developer.android.com/jetpack/androidx/releases/datastore",
        LicenseKind.APACHE_2_0,
    ),
    LicenseEntry(
        "AndroidX Window",
        "1.3.0",
        "Apache 2.0",
        "https://developer.android.com/jetpack/androidx/releases/window",
        LicenseKind.APACHE_2_0,
    ),
    LicenseEntry(
        "AndroidX Biometric",
        "1.1.0",
        "Apache 2.0",
        "https://developer.android.com/jetpack/androidx/releases/biometric",
        LicenseKind.APACHE_2_0,
    ),
    LicenseEntry(
        "AndroidX Fragment KTX",
        "1.8.5",
        "Apache 2.0",
        "https://developer.android.com/jetpack/androidx/releases/fragment",
        LicenseKind.APACHE_2_0,
    ),
    LicenseEntry(
        "AndroidX Security Crypto",
        "1.1.0-alpha06",
        "Apache 2.0",
        "https://developer.android.com/jetpack/androidx/releases/security",
        LicenseKind.APACHE_2_0,
    ),

    // --- DI ---
    LicenseEntry(
        "Hilt (Dagger)",
        "2.52",
        "Apache 2.0",
        "https://dagger.dev/hilt/",
        LicenseKind.APACHE_2_0,
    ),
    LicenseEntry(
        "Hilt Navigation Compose",
        "1.2.0",
        "Apache 2.0",
        "https://developer.android.com/jetpack/androidx/releases/hilt",
        LicenseKind.APACHE_2_0,
    ),
    LicenseEntry(
        "javax.inject",
        "1",
        "Apache 2.0",
        "https://github.com/javax-inject/javax-inject",
        LicenseKind.APACHE_2_0,
    ),

    // --- Kotlin ---
    LicenseEntry(
        "KotlinX Coroutines",
        "1.9.0",
        "Apache 2.0",
        "https://github.com/Kotlin/kotlinx.coroutines",
        LicenseKind.APACHE_2_0,
    ),
    LicenseEntry(
        "KotlinX Serialization JSON",
        "1.7.3",
        "Apache 2.0",
        "https://github.com/Kotlin/kotlinx.serialization",
        LicenseKind.APACHE_2_0,
    ),

    // --- HTTP ---
    LicenseEntry(
        "Retrofit",
        "2.11.0",
        "Apache 2.0",
        "https://square.github.io/retrofit/",
        LicenseKind.APACHE_2_0,
    ),
    LicenseEntry(
        "Retrofit KotlinX Serialization Converter",
        "1.0.0",
        "Apache 2.0",
        "https://github.com/JakeWharton/retrofit2-kotlinx-serialization-converter",
        LicenseKind.APACHE_2_0,
    ),
    LicenseEntry(
        "OkHttp",
        "4.12.0",
        "Apache 2.0",
        "https://square.github.io/okhttp/",
        LicenseKind.APACHE_2_0,
    ),
    LicenseEntry(
        "OkHttp Logging Interceptor",
        "4.12.0",
        "Apache 2.0",
        "https://square.github.io/okhttp/",
        LicenseKind.APACHE_2_0,
    ),
)

@Composable
fun LicensesSection(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LICENSES.forEach { entry ->
            LicenseCard(entry)
        }
    }
}

@Composable
private fun LicenseCard(entry: LicenseEntry) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Version ${entry.version} · ${entry.license}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = entry.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace,
            )
            if (expanded) {
                if (entry.notice != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = entry.notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = licenseTextFor(entry.kind),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private fun licenseTextFor(kind: LicenseKind): String = when (kind) {
    LicenseKind.APACHE_2_0 -> APACHE_2_0_TEXT
    LicenseKind.MIT_CJSON -> MIT_CJSON_TEXT
}
