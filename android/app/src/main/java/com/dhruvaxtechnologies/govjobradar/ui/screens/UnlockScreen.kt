package com.dhruvaxtechnologies.govjobradar.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.dhruvaxtechnologies.govjobradar.GovJobApp
import com.dhruvaxtechnologies.govjobradar.ui.theme.Ok

fun Context.findActivity(): Activity? {
    var c = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnlockScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as GovJobApp
    val unlocked by app.billing.unlocked.collectAsState()
    val price by app.billing.price.collectAsState()
    val msg by app.billing.message.collectAsState()
    LaunchedEffect(Unit) { app.billing.clearMessage(); app.billing.start() }
    val days = app.billing.trialDaysLeft()

    Scaffold(topBar = {
        TopAppBar(title = { Text("GovJob Radar full app") },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(20.dp)) {
            if (unlocked) {
                Icon(Icons.Outlined.CheckCircle, null, Modifier.size(48.dp), tint = Ok)
                Text("Full app unlocked", style = MaterialTheme.typography.titleLarge)
                Text("Thank you for supporting GovJob Radar.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            Text(if (days > 0) "Your free trial: $days day(s) left" else "Your free trial has ended",
                style = MaterialTheme.typography.titleLarge)
            Text("Unlock once to keep everything. No subscription, no ads.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            listOf(
                Icons.Outlined.Description to "Full details of every vacancy: posts, pay level, qualification, age, fee, how to apply",
                Icons.Outlined.Badge to "\"Am I eligible?\" check with age relaxation and pay level for deputation",
                Icons.Outlined.Bookmarks to "Save jobs, with reminders 3 days and 1 day before the last date",
                Icons.Outlined.NotificationsActive to "Morning alerts for new jobs and last-date extensions",
                Icons.Outlined.Search to "Search across all vacancies",
            ).forEach { (icon, text) ->
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text("Free for everyone, always: the job lists, last dates and the link to every official notification.",
                Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))
            Button(onClick = { ctx.findActivity()?.let { app.billing.buy(it) } }, modifier = Modifier.fillMaxWidth()) {
                Text(price?.let { "Unlock for $it" } ?: "Unlock full app", fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = { app.billing.restore() }, modifier = Modifier.fillMaxWidth()) { Text("Restore purchase") }
            msg?.let { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.primary) }
            Text("Payment is handled by Google Play. One-time purchase, linked to your Google account.",
                Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
