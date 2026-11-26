package com.dhruvaxtechnologies.govjobradar.ui.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.dhruvaxtechnologies.govjobradar.GovJobApp
import com.dhruvaxtechnologies.govjobradar.data.Catalog
import com.dhruvaxtechnologies.govjobradar.data.Eligibility
import com.dhruvaxtechnologies.govjobradar.data.Job
import com.dhruvaxtechnologies.govjobradar.sync.Scheduler
import com.dhruvaxtechnologies.govjobradar.ui.Badge
import com.dhruvaxtechnologies.govjobradar.ui.Disclaimer
import com.dhruvaxtechnologies.govjobradar.ui.EmptyState
import com.dhruvaxtechnologies.govjobradar.ui.InfoBanner
import com.dhruvaxtechnologies.govjobradar.ui.deadlineColor
import com.dhruvaxtechnologies.govjobradar.ui.deadlineText
import com.dhruvaxtechnologies.govjobradar.ui.fmtDate
import com.dhruvaxtechnologies.govjobradar.ui.openUrl
import com.dhruvaxtechnologies.govjobradar.ui.theme.Danger
import com.dhruvaxtechnologies.govjobradar.ui.theme.Ok
import com.dhruvaxtechnologies.govjobradar.ui.theme.Warn
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun JobDetailScreen(nav: NavHostController, id: String) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as GovJobApp
    val feed by app.repo.feed.collectAsState()
    val profile by app.prefs.profile.collectAsState()
    val saved by app.prefs.saved.collectAsState()
    val job = remember(feed, id) { app.repo.job(id) }
    var reporting by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(title = { Text(job?.let { Catalog.sector(it.sector).label } ?: "Job") },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                if (job != null) {
                    IconButton(onClick = { app.prefs.toggleSaved(job.id); Scheduler.syncReminders(app) }) {
                        Icon(if (job.id in saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, "Save")
                    }
                    IconButton(onClick = { share(ctx, job) }) { Icon(Icons.Outlined.Share, "Share") }
                }
            })
    }) { pad ->
        if (job == null) {
            Column(Modifier.padding(pad)) { EmptyState("This job is no longer listed", "It may have closed or been removed.") }
            return@Scaffold
        }
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(16.dp)) {
                Text(job.title, style = MaterialTheme.typography.titleLarge)
                Text(job.organisation + (job.state?.let { " · $it" } ?: ""), color = MaterialTheme.colorScheme.onSurfaceVariant)
                job.advtNo?.let { Text("Advt. No. $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Badge(if (job.level == "central") "CENTRAL GOVT" else "STATE GOVT", MaterialTheme.colorScheme.primary)
                    if (job.isDeputation) Badge("DEPUTATION", Color(0xFF6A1B9A))
                    if (job.extended) Badge("DATE EXTENDED", Warn)
                    if (job.isNew()) Badge("NEW", MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(8.dp))
                Text(deadlineText(job), color = deadlineColor(job.daysLeft()), fontWeight = FontWeight.Bold)
                if (job.lastDate != null) job.lastDateNote?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (job.awaitingExtension) InfoBanner("The last date (${fmtDate(job.lastDate)}) has passed. We are checking the official " +
                "website for an extension; this job will be removed if none is announced.", MaterialTheme.colorScheme.outline)
            if (job.extended) {
                val first = job.extensionHistory?.firstOrNull()?.from ?: job.originalLastDate
                InfoBanner("Last date extended from ${fmtDate(first)} to ${fmtDate(job.lastDate)} by an official corrigendum.", Warn) {
                    openUrl(ctx, job.extensionHistory?.lastOrNull()?.noticeUrl)
                }
            }
            if (job.status == "closed") InfoBanner("Closed on ${fmtDate(job.lastDate)}. Kept here for reference.", MaterialTheme.colorScheme.outline)

            EligibilityCard(job, profile, onEdit = { nav.navigate("profile") })

            DetailCard("Key facts") {
                Fact("Last date", fmtDate(job.lastDate))
                job.startDate?.let { Fact("Applications from", fmtDate(it)) }
                job.totalVacancies?.let { Fact("Vacancies", "$it") }
                if (job.payLevelMin != null) Fact("Pay level", if (job.payLevelMax != null && job.payLevelMax != job.payLevelMin)
                    "Level ${job.payLevelMin}–${job.payLevelMax} (7th CPC)" else "Level ${job.payLevelMin} (7th CPC)")
                job.qualificationSummary?.let { Fact("Qualification", it) }
                if (job.ageMax != null || job.ageMin != null) Fact("Age", listOfNotNull(job.ageMin?.let { "min $it" },
                    job.ageMax?.let { "max $it" }).joinToString(", ") + (job.ageAsOn?.let { " (as on ${fmtDate(it)})" } ?: ""))
                job.ageNote?.let { Fact("Age relaxation", it) }
                job.feeText?.let { Fact("Fee", it) }
                job.applyMode?.let { Fact("Apply", it.replaceFirstChar { c -> c.uppercase() }) }
                job.location?.let { Fact("Location", it) }
                job.deputationTenure?.let { Fact("Deputation period", it) }
                job.eligibleCadre?.let { Fact("Who can apply", it) }
            }

            job.summary?.let { DetailCard("About") { Text(it) } }

            if (job.posts.isNotEmpty()) DetailCard("Posts") {
                job.posts.forEachIndexed { i, p ->
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Text(p.name, fontWeight = FontWeight.SemiBold)
                    Text(listOfNotNull(p.vacancies?.let { "$it post(s)" }, p.payLevel?.let { "Level $it" }, p.payText)
                        .joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    p.qualification?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            }

            job.howToApply?.let { DetailCard("How to apply") { Text(it) } }

            Column(Modifier.padding(16.dp)) {
                Button(onClick = { openUrl(ctx, job.noticeUrl) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.PictureAsPdf, null); Spacer(Modifier.width(8.dp)); Text("Read official notification")
                }
                job.detailUrl?.takeIf { it != job.noticeUrl }?.let { url ->
                    OutlinedButton(onClick = { openUrl(ctx, url) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text("Official notice page (all documents)")
                    }
                }
                job.applyUrl?.let { url ->
                    OutlinedButton(onClick = { openUrl(ctx, url) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text("Open official apply page")
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Source: ${job.sourceName} · official link checked ${fmtDate(job.verifiedAt)}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Summary prepared automatically from the official notification. " + Disclaimer.SHORT,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { reporting = true }) {
                    Icon(Icons.Outlined.Flag, null); Spacer(Modifier.width(6.dp)); Text("Report wrong information")
                }
            }
        }
        if (reporting) ReportDialog(job, app) { reporting = false }
    }
}

@Composable
private fun ReportDialog(job: Job, app: GovJobApp, onClose: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = onClose,
        title = { Text(if (sent) "Thank you" else "What is wrong?") },
        text = {
            if (sent) Text("Your report was sent. The job will be checked against the official notice.")
            else OutlinedTextField(text, { text = it.take(500) }, placeholder = { Text("e.g. last date is 20 Dec, link not opening") },
                minLines = 3)
        },
        confirmButton = {
            if (sent) TextButton(onClick = onClose) { Text("Close") }
            else TextButton(enabled = text.isNotBlank(), onClick = {
                scope.launch { app.reporter.report("USER_REPORT", "${job.title} (${job.sourceName}): $text", "warning", job.id) }
                sent = true
            }) { Text("Send") }
        },
        dismissButton = { if (!sent) TextButton(onClick = onClose) { Text("Cancel") } })
}

@Composable
private fun EligibilityCard(job: Job, p: com.dhruvaxtechnologies.govjobradar.data.Profile, onEdit: () -> Unit) {
    val e = Eligibility.likelyEligible(job, p)
    val (title, color) = when (e) {
        true -> "You look eligible" to Ok
        false -> "You may not be eligible" to Danger
        null -> "Eligibility: check the notification" to MaterialTheme.colorScheme.outline
    }
    val notes = buildList {
        if (job.isDeputation) {
            if (!p.govtEmployee) add("Deputation posts are only for serving government employees.")
            when (Eligibility.payLevelOk(job, p)) { true -> add("Your pay level fits this post."); false -> add("Your pay level (${p.payLevel}) is outside the usual range for this post."); null -> {} }
        } else {
            when (Eligibility.qualificationOk(job, p)) { false -> add("Qualification needed: ${job.qualificationSummary ?: job.minQualification}"); else -> {} }
            when (Eligibility.ageOk(job, p)) {
                false -> add("Age limit: ${job.ageMax ?: "-"} (+${Eligibility.relaxation(p)} relaxation for you)")
                true -> add("Your age fits (including ${Eligibility.relaxation(p)} years relaxation)")
                null -> {}
            }
        }
        if (job.level == "state" && p.homeState != null && job.state != null && job.state != p.homeState) add("State post: domicile of ${job.state} may be required.")
        if (p.dob == null && !job.isDeputation) add("Add your date of birth and qualification to check automatically.")
    }
    Card(onClick = onEdit, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f))) {
        Column(Modifier.padding(12.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = color)
            notes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            Text("Estimate only · relaxation uses common central norms.", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DetailCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(1.dp)) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(label, Modifier.width(130.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

private fun share(ctx: android.content.Context, job: Job) {
    val text = "${job.title} — ${job.organisation}\nLast date: ${fmtDate(job.lastDate)}\nOfficial notice: ${job.noticeUrl}\n\nvia GovJob Radar"
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share job")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
