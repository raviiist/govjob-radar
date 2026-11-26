package com.dhruvaxtechnologies.govjobradar.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.dhruvaxtechnologies.govjobradar.GovJobApp
import com.dhruvaxtechnologies.govjobradar.admin.ErrorItem
import com.dhruvaxtechnologies.govjobradar.admin.Health
import com.dhruvaxtechnologies.govjobradar.admin.ReviewItem
import com.dhruvaxtechnologies.govjobradar.data.Catalog
import com.dhruvaxtechnologies.govjobradar.ui.Badge
import com.dhruvaxtechnologies.govjobradar.ui.EmptyState
import com.dhruvaxtechnologies.govjobradar.ui.fmtDate
import com.dhruvaxtechnologies.govjobradar.ui.openUrl
import com.dhruvaxtechnologies.govjobradar.ui.theme.Danger
import com.dhruvaxtechnologies.govjobradar.ui.theme.Ok
import com.dhruvaxtechnologies.govjobradar.ui.theme.Warn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

private fun sevColor(s: String) = when (s) {
    "critical" -> Color(0xFFB71C1C); "error" -> Danger; "warning" -> Warn; else -> Color(0xFF607D8B)
}

private fun ago(iso: String?): String = runCatching {
    val m = Duration.between(Instant.parse(iso), Instant.now()).toMinutes()
    when { m < 60 -> "$m min ago"; m < 48 * 60 -> "${m / 60} h ago"; else -> "${m / 1440} days ago" }
}.getOrDefault(iso ?: "never")

private fun hoursSince(iso: Any?): Long? = runCatching { Duration.between(Instant.parse(iso as String), Instant.now()).toHours() }.getOrNull()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(nav: NavHostController) {
    val app = LocalContext.current.applicationContext as GovJobApp
    if (!app.admin.isAdmin()) {
        Column(Modifier.fillMaxSize()) { EmptyState("Admin only", "Sign in with the developer account in Settings.") }
        return
    }
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var review by remember { mutableStateOf<List<ReviewItem>>(emptyList()) }
    var errors by remember { mutableStateOf<List<ErrorItem>>(emptyList()) }
    var health by remember { mutableStateOf<Health?>(null) }
    var live by remember { mutableStateOf<List<ReviewItem>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        loading = true
        error = null
        try {
            review = app.admin.reviewQueue()
            errors = app.admin.errors()
            health = app.admin.health()
            live = app.admin.liveJobs()
        } catch (e: Exception) {
            error = "Could not load admin data: ${e.message}"
        }
        loading = false
    }
    val act: (suspend () -> Unit) -> Unit = { block ->
        scope.launch { try { block(); reload++ } catch (e: Exception) { error = e.message } }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Admin") },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { IconButton(onClick = { reload++ }) { Icon(Icons.Outlined.Refresh, "Reload") } })
    }) { pad ->
        Column(Modifier.padding(pad)) {
            val serverRed = health?.let { (hoursSince(it.server["last_success"]) ?: 999) > 30 } ?: false
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Review (${review.size})") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Errors (${errors.size})", color = if (errors.any { it.severity in setOf("critical", "error") }) Danger else Color.Unspecified) })
                Tab(tab == 2, { tab = 2 }, text = { Text("Health", color = if (serverRed) Danger else Color.Unspecified) })
                Tab(tab == 3, { tab = 3 }, text = { Text("Live") })
            }
            error?.let { Text(it, Modifier.padding(12.dp), color = Danger) }
            if (loading) Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else when (tab) {
                0 -> ReviewTab(review, act, app)
                1 -> ErrorsTab(errors, act, app)
                2 -> health?.let { HealthTab(it, act, app) }
                else -> LiveTab(live, act, app)
            }
        }
    }
}

@Composable
private fun ReviewTab(items: List<ReviewItem>, act: (suspend () -> Unit) -> Unit, app: GovJobApp) {
    val ctx = LocalContext.current
    var editing by remember { mutableStateOf<ReviewItem?>(null) }
    var rejecting by remember { mutableStateOf<ReviewItem?>(null) }
    if (items.isEmpty()) { EmptyState("Nothing to review", "All new notices passed the checks and went live automatically."); return }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Text("Approved items reach users at the next publish (within about an hour).", Modifier.padding(12.dp),
                style = MaterialTheme.typography.labelSmall)
        }
        val clean = items.filter { app.admin.onlyNewSource(it) }
        if (clean.isNotEmpty()) item {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                colors = CardDefaults.cardColors(containerColor = Ok.copy(alpha = 0.10f))) {
                Column(Modifier.padding(12.dp)) {
                    Text("${clean.size} item(s) passed every check", fontWeight = FontWeight.Bold)
                    Text("They wait here only because their website is new this week. Open a few to spot-check, then approve them together.",
                        style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { act { app.admin.approveAll(clean) } }, modifier = Modifier.padding(top = 6.dp)) {
                        Text("Approve all ${clean.size}")
                    }
                }
            }
        }
        items(items, key = { it.id }) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), elevation = CardDefaults.cardElevation(2.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(it.title, style = MaterialTheme.typography.titleMedium)
                    Text("${it.organisation} · ${it.sourceName}", style = MaterialTheme.typography.bodyMedium)
                    Text("${Catalog.sector(it.sector).label} · ${it.level}${it.state?.let { s -> " · $s" } ?: ""} · ${it.kind}" +
                        " · last date ${fmtDate(it.lastDate)} · ${it.totalVacancies ?: "?"} posts" +
                        (it.confidence?.let { c -> " · AI ${(c * 100).toInt()}%" } ?: ""),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    it.advtNo?.let { a -> Text("Advt: $a", style = MaterialTheme.typography.labelSmall) }
                    Spacer(Modifier.height(6.dp))
                    it.reasons.forEach { r -> Text("⚠ $r", color = Warn, style = MaterialTheme.typography.bodyMedium) }
                    it.summary?.let { s -> Text(s, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { openUrl(ctx, it.noticeUrl) }, modifier = Modifier.fillMaxWidth()) { Text("Open official notice") }
                    if (it.pendingExtensionTo != null) {
                        Text("Extend last date ${fmtDate(it.lastDate)} → ${fmtDate(it.pendingExtensionTo)}?", fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 6.dp))
                        it.pendingExtensionUrl?.let { u -> TextButton(onClick = { openUrl(ctx, u) }) { Text("Open corrigendum / apply page") } }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { act { app.admin.acceptExtension(it) } }, modifier = Modifier.weight(1f)) { Text("Accept extension") }
                            OutlinedButton(onClick = { act { app.admin.rejectExtension(it) } }, modifier = Modifier.weight(1f)) { Text("Not an extension") }
                        }
                    }
                    if (it.status == "pending_review") Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        Button(onClick = { act { app.admin.approve(it) } }, modifier = Modifier.weight(1f)) { Text("Approve") }
                        OutlinedButton(onClick = { editing = it }, modifier = Modifier.weight(1f)) { Text("Fix") }
                        OutlinedButton(onClick = { rejecting = it }, modifier = Modifier.weight(1f)) { Text("Reject", color = Danger) }
                    }
                }
            }
        }
    }
    editing?.let { item -> EditDialog(item, onDismiss = { editing = null }) { edits -> act { app.admin.approve(item, edits) }; editing = null } }
    rejecting?.let { item ->
        var note by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { rejecting = null }, title = { Text("Reject?") },
            text = { OutlinedTextField(note, { note = it }, label = { Text("Reason (optional)") }) },
            confirmButton = { TextButton(onClick = { act { app.admin.reject(item, note) }; rejecting = null }) { Text("Reject") } },
            dismissButton = { TextButton(onClick = { rejecting = null }) { Text("Cancel") } })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditDialog(item: ReviewItem, onDismiss: () -> Unit, onSave: (Map<String, Any?>) -> Unit) {
    var title by remember { mutableStateOf(item.title) }
    var last by remember { mutableStateOf(item.lastDate ?: "") }
    var vac by remember { mutableStateOf(item.totalVacancies?.toString() ?: "") }
    var sector by remember { mutableStateOf(item.sector) }
    var kind by remember { mutableStateOf(item.kind) }
    var level by remember { mutableStateOf(item.level) }
    var sectorMenu by remember { mutableStateOf(false) }
    val dateOk = runCatching { LocalDate.parse(last) }.isSuccess
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Fix and approve") },
        text = {
            Column {
                OutlinedTextField(title, { title = it }, label = { Text("Title") })
                OutlinedTextField(last, { last = it }, label = { Text("Last date (YYYY-MM-DD)") }, isError = !dateOk)
                OutlinedTextField(vac, { vac = it.filter(Char::isDigit) }, label = { Text("Total vacancies") })
                Box {
                    OutlinedButton(onClick = { sectorMenu = true }) { Text(Catalog.sector(sector).label + " ▾") }
                    DropdownMenu(sectorMenu, { sectorMenu = false }) {
                        Catalog.sectors.forEach { s -> DropdownMenuItem({ Text(s.label) }, { sector = s.id; sectorMenu = false }) }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(level == "central", { level = "central" }, { Text("Central") })
                    FilterChip(level == "state", { level = "state" }, { Text("State") })
                    FilterChip(kind == "recruitment", { kind = "recruitment" }, { Text("Recruitment") })
                    FilterChip(kind == "deputation", { kind = "deputation" }, { Text("Deputation") })
                }
            }
        },
        confirmButton = {
            TextButton(enabled = dateOk && title.isNotBlank(), onClick = {
                onSave(mapOf("title" to title.trim(), "last_date" to last, "total_vacancies" to vac.toLongOrNull(),
                    "sector" to sector, "kind" to kind, "level" to level))
            }) { Text("Save & approve") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun ErrorsTab(items: List<ErrorItem>, act: (suspend () -> Unit) -> Unit, app: GovJobApp) {
    val ctx = LocalContext.current
    if (items.isEmpty()) { EmptyState("No open problems", "Server, GitHub jobs and phones are all working."); return }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        items(items, key = { it.collection + it.docId }) { e ->
            Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                colors = CardDefaults.cardColors(containerColor = sevColor(e.severity).copy(alpha = 0.07f))) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Badge(e.severity.uppercase(), sevColor(e.severity))
                        Spacer(Modifier.width(6.dp))
                        Badge(when (e.origin) { "app" -> "PHONES"; "user_report" -> "USER"; "workflow" -> "GITHUB"; else -> "SERVER" },
                            MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.width(6.dp))
                        Text(e.code, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(e.message, style = MaterialTheme.typography.bodyMedium, maxLines = 12)
                    e.hint?.let { Text("What to do: $it", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary) }
                    Text(listOfNotNull("${e.count}×", "last ${ago(e.lastAt)}", e.sourceId?.let { "source $it" }, e.device)
                        .joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row {
                        e.runUrl?.let { u -> TextButton(onClick = { openUrl(ctx, u) }) { Text("Open GitHub run") } }
                        e.jobId?.let { id -> app.repo.job(id)?.let { j -> TextButton(onClick = { openUrl(ctx, j.noticeUrl) }) { Text("Open notice") } } }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { act { app.admin.resolve(e) } }) { Text("Mark fixed") }
                    }
                }
            }
        }
    }
}

@Composable
private fun HealthTab(h: Health, act: (suspend () -> Unit) -> Unit, app: GovJobApp) {
    val ctx = LocalContext.current
    val s = h.server
    val serverAge = hoursSince(s["last_success"])
    val pubAge = hoursSince(h.publish["last_built"])
    LazyColumn(contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            StatusCard("Nightly crawl", serverAge == null || serverAge > 30,
                if (serverAge == null) "Has never completed. Check GitHub Actions > nightly and the repository secrets."
                else "Last success ${ago(s["last_success"] as String)} · took ${s["duration_min"] ?: "?"} min · state: ${s["state"]}" +
                    (if (serverAge > 30) "\nNOT RUN IN LAST 30 HOURS — the GitHub job is failing or disabled." else ""),
                s["run_url"] as? String)
        }
        item {
            StatusCard("Feed publish", pubAge == null || pubAge > 5,
                if (pubAge == null) "Never published." else "Built ${ago(h.publish["last_built"] as String)} · ${h.publish["jobs_live"]} live jobs · " +
                    "${h.publish["archive"]} closed · ${((h.publish["bytes"] as? Number)?.toLong() ?: 0) / 1024} KB",
                h.publish["run_url"] as? String)
        }
        item {
            @Suppress("UNCHECKED_CAST") val st = s["stats"] as? Map<String, Any?> ?: emptyMap()
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Last night", fontWeight = FontWeight.Bold)
                    Text("Sources ok ${st["sources_ok"]}/${st["sources_total"]} · new notices ${st["notices_seen"]} · read ${st["notices_read"]}\n" +
                        "Published ${st["published"]} · to review ${st["sent_to_review"]} · dropped ${st["dropped"]}\n" +
                        "Extensions ${st["extensions"]} · closed ${st["closed"]} · links broken ${st["links_broken"]}\n" +
                        "AI: ${s["ai_calls"]} calls (${s["model"]})" + (s["ai_stopped"]?.let { " · STOPPED: $it" } ?: "") +
                        ((st["deferred_for_quota"] as? Number)?.toInt()?.takeIf { it > 0 }?.let { " · $it waiting for tomorrow" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Publishing rules", fontWeight = FontWeight.Bold)
                    SettingSwitch("Auto-publish jobs that pass every check", h.settings["auto_publish"] as? Boolean ?: true) { v ->
                        act { app.admin.setSetting("auto_publish", v) }
                    }
                    SettingSwitch("Review everything from a new source for its first week", h.settings["review_new_sources"] as? Boolean ?: true) { v ->
                        act { app.admin.setSetting("review_new_sources", v) }
                    }
                }
            }
        }
        item {
            val configured = (s["sources_configured"] as? Number)?.toInt()
            val okCount = h.sources.count { ((it["fail_count"] as? Number)?.toInt() ?: 0) == 0 }
            Text("Sources: $okCount working of ${configured ?: h.sources.size}" +
                (if (configured != null && configured > h.sources.size) " (${configured - h.sources.size} not crawled yet)" else ""),
                fontWeight = FontWeight.Bold)
        }
        items(h.sources.sortedByDescending { (it["fail_count"] as? Number)?.toInt() ?: 0 }) { src ->
            val fails = (src["fail_count"] as? Number)?.toInt() ?: 0
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (fails == 0) "●" else "●", color = when { fails == 0 -> Ok; fails < 3 -> Warn; else -> Danger })
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("${src["name"] ?: src["id"]}", fontWeight = FontWeight.SemiBold)
                    Text(if (fails == 0) "ok · ${src["last_candidates"] ?: 0} notices on page · ${ago(src["last_ok"] as? String)}"
                         else "failing $fails night(s): ${src["last_error"]}", style = MaterialTheme.typography.labelSmall)
                }
                (src["listing_url"] as? String)?.let { u -> TextButton(onClick = { openUrl(ctx, u) }) { Text("Open") } }
            }
        }
    }
}

@Composable
private fun StatusCard(title: String, bad: Boolean, text: String, url: String?) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = (if (bad) Danger else Ok).copy(alpha = 0.10f))) {
        Column(Modifier.padding(12.dp)) {
            Text((if (bad) "✖ " else "✔ ") + title, fontWeight = FontWeight.Bold, color = if (bad) Danger else Ok)
            Text(text, style = MaterialTheme.typography.bodyMedium)
            url?.let { TextButton(onClick = { openUrl(ctx, it) }) { Text("Open last GitHub run") } }
        }
    }
}

@Composable
private fun SettingSwitch(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    var v by remember(value) { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(v, { v = it; onChange(it) })
    }
}

@Composable
private fun LiveTab(items: List<ReviewItem>, act: (suspend () -> Unit) -> Unit, app: GovJobApp) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    val shown = items.filter { q.isBlank() || it.title.contains(q, true) || it.organisation.contains(q, true) }
        .sortedBy { it.lastDate ?: "9999" }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item { OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(12.dp), label = { Text("Find a live job") }) }
        item { Text("${shown.size} jobs. 'Remove' takes a job out for good at the next publish (use it for anything that is not a real vacancy).",
            Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall) }
        items(shown, key = { it.id }) { j ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(j.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    Text("${j.sourceName} · ${fmtDate(j.lastDate)} · ${j.status}", style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = { openUrl(ctx, j.noticeUrl) }) { Text("Open") }
                TextButton(onClick = { act { app.admin.unpublish(j) } }) { Text("Remove", color = Danger) }
            }
        }
    }
}
