package com.crdp.feature.connections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private data class LicenseEntry(
    val name: String,
    val version: String,
    val license: String,
    val url: String,
)

private val LICENSES = listOf(
    LicenseEntry(
        "Jetpack Compose (BOM)",
        "2024.12.01",
        "Apache 2.0",
        "https://developer.android.com/jetpack/compose",
    ),
    LicenseEntry(
        "Material 3",
        "1.x",
        "Apache 2.0",
        "https://developer.android.com/jetpack/androidx/releases/compose-material3",
    ),
    LicenseEntry(
        "Material 3 Window Size Class",
        "1.3.1",
        "Apache 2.0",
        "https://developer.android.com/jetpack/androidx/releases/compose-material3",
    ),
    LicenseEntry(
        "AndroidX Core KTX",
        "1.15.0",
        "Apache 2.0",
        "https://developer.android.com/jetpack/androidx/releases/core",
    ),
    LicenseEntry(
        "AndroidX Activity Compose",
        "1.9.3",
        "Apache 2.0",
        "https://developer.android.com/jetpack/androidx/releases/activity",
    ),
    LicenseEntry(
        "AndroidX Lifecycle",
        "2.8.7",
        "Apache 2.0",
        "https://developer.android.com/jetpack/androidx/releases/lifecycle",
    ),
    LicenseEntry(
        "AndroidX Navigation Compose",
        "2.8.4",
        "Apache 2.0",
        "https://developer.android.com/jetpack/androidx/releases/navigation",
    ),
    LicenseEntry(
        "AndroidX DataStore Preferences",
        "1.1.1",
        "Apache 2.0",
        "https://developer.android.com/jetpack/androidx/releases/datastore",
    ),
    LicenseEntry(
        "AndroidX Window",
        "1.3.0",
        "Apache 2.0",
        "https://developer.android.com/jetpack/androidx/releases/window",
    ),
    LicenseEntry(
        "AndroidX Biometric",
        "1.1.0",
        "Apache 2.0",
        "https://developer.android.com/jetpack/androidx/releases/biometric",
    ),
    LicenseEntry(
        "AndroidX Fragment KTX",
        "1.8.5",
        "Apache 2.0",
        "https://developer.android.com/jetpack/androidx/releases/fragment",
    ),
    LicenseEntry(
        "Hilt (Dagger)",
        "2.52",
        "Apache 2.0",
        "https://dagger.dev/hilt/",
    ),
    LicenseEntry(
        "Hilt Navigation Compose",
        "1.2.0",
        "Apache 2.0",
        "https://developer.android.com/jetpack/androidx/releases/hilt",
    ),
    LicenseEntry(
        "KotlinX Coroutines",
        "1.9.0",
        "Apache 2.0",
        "https://github.com/Kotlin/kotlinx.coroutines",
    ),
    LicenseEntry(
        "KotlinX Serialization JSON",
        "1.7.3",
        "Apache 2.0",
        "https://github.com/Kotlin/kotlinx.serialization",
    ),
    LicenseEntry(
        "Retrofit",
        "2.11.0",
        "Apache 2.0",
        "https://square.github.io/retrofit/",
    ),
    LicenseEntry(
        "OkHttp",
        "4.12.0",
        "Apache 2.0",
        "https://square.github.io/okhttp/",
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
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
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
            Spacer(Modifier.height(6.dp))
            Text(
                text = entry.url,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
