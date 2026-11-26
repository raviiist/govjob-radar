package com.dhruvaxtechnologies.govjobradar.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.dhruvaxtechnologies.govjobradar.BuildConfig
import com.dhruvaxtechnologies.govjobradar.GovJobApp
import com.dhruvaxtechnologies.govjobradar.sync.Notifier
import com.dhruvaxtechnologies.govjobradar.ui.SectionTitle
import com.dhruvaxtechnologies.govjobradar.ui.openUrl
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as GovJobApp
    val feed by app.repo.feed.collectAsState()
    val scope = rememberCoroutineScope()
    var adminMsg by remember { mutableStateOf<String?>(null) }
    var adminEmail by remember { mutableStateOf(app.admin.signedInEmail()) }
    val site = BuildConfig.FEED_BASE_URL.trimEnd('/')

    Scaffold(topBar = {
        TopAppBar(title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState())) {
            Row("My profile", "State, date of birth, qualification, category, pay level, notifications") { nav.navigate("profile") }
            val unlocked by app.billing.unlocked.collectAsState()
            Row(if (unlocked) "Full app unlocked ✓" else "Unlock full app",
                if (unlocked) "Thank you for your support" else app.billing.trialDaysLeft().let {
                    if (it > 0) "Free trial: $it day(s) left · restore an earlier purchase here" else "Free trial ended · or restore an earlier purchase"
                }) { nav.navigate("unlock") }
            if (!Notifier.allowed(ctx)) Row("Notifications are off", "Tap to allow reminders and the morning summary") {
                ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            Row("Battery: allow background updates", "If updates stop, set battery usage for this app to \"Unrestricted\"") {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            val ok = app.prefs.lastSyncOk
            Text("Last successful update on this phone: " + (if (ok == 0L) "never" else DateFormat.getDateTimeInstance().format(Date(ok))) +
                "\nJobs in list: ${feed.jobs.size} · closed (last 30 days): ${feed.archive.size}",
                Modifier.padding(16.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()

            SectionTitle("About & disclaimer")
            com.dhruvaxtechnologies.govjobradar.ui.DisclaimerContent(Modifier.padding(horizontal = 16.dp))
            Row("Privacy policy", "$site/privacy.html") { openUrl(ctx, "$site/privacy.html") }
            Row("Share GovJob Radar", "Tell friends and colleagues") {
                val link = "https://play.google.com/store/apps/details?id=${ctx.packageName}"
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, "GovJob Radar – Central, State and Deputation government vacancies, " +
                        "updated every night from official websites: $link"), "Share app").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            Row("Official sources (${feed.sources.size})", "Websites checked every night") { }
            feed.sources.sortedBy { it.name }.forEach { s ->
                Text("• ${s.name}${s.state?.let { " ($it)" } ?: ""}", Modifier.fillMaxWidth().clickable { openUrl(ctx, s.home) }
                    .padding(horizontal = 24.dp, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
            var taps by remember { mutableStateOf(0) }
            Text("Version ${BuildConfig.VERSION_NAME}", Modifier.fillMaxWidth().clickable { taps++ }.padding(16.dp),
                style = MaterialTheme.typography.labelSmall)
            HorizontalDivider()

            // Small, unobtrusive: only the developer's Google account can actually open the Admin tab.
            // Developer-only: hidden until the version line is tapped 7 times (or already signed in).
            if (app.admin.isConfigured && (taps >= 7 || adminEmail != null)) {
                if (adminEmail != null && app.admin.isAdmin()) {
                    Row("Admin", "Signed in as $adminEmail") { nav.navigate("admin") }
                    Row("Admin sign out", "") { scope.launch { app.admin.signOut(); adminEmail = null } }
                } else {
                    Row("Developer sign-in", adminMsg ?: "For the app developer only") {
                        scope.launch {
                            app.admin.signIn(ctx).onSuccess { adminEmail = it; adminMsg = null; nav.navigate("admin") }
                                .onFailure { adminMsg = it.message ?: "Sign-in failed" }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Row(title: String, sub: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, fontWeight = FontWeight.SemiBold)
        if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
