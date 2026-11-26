package com.dhruvaxtechnologies.govjobradar.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHoriz
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.dhruvaxtechnologies.govjobradar.GovJobApp
import com.dhruvaxtechnologies.govjobradar.data.Catalog
import com.dhruvaxtechnologies.govjobradar.data.Eligibility
import com.dhruvaxtechnologies.govjobradar.data.Job
import com.dhruvaxtechnologies.govjobradar.data.Sector
import com.dhruvaxtechnologies.govjobradar.data.SyncResult
import com.dhruvaxtechnologies.govjobradar.ui.EmptyState
import com.dhruvaxtechnologies.govjobradar.ui.InfoBanner
import com.dhruvaxtechnologies.govjobradar.ui.JobCard
import com.dhruvaxtechnologies.govjobradar.ui.SectionTitle
import com.dhruvaxtechnologies.govjobradar.ui.listRoute
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: NavHostController, initialTab: String) {
    val app = LocalContext.current.applicationContext as GovJobApp
    val feed by app.repo.feed.collectAsState()
    val profile by app.prefs.profile.collectAsState()
    val saved by app.prefs.saved.collectAsState()
    var tab by rememberSaveable { mutableStateOf(if (initialTab == "new") "my" else initialTab) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var isAdmin by remember { mutableStateOf(app.admin.isAdmin()) }
    // Re-check when coming back from Settings (e.g. right after developer sign-in).
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        isAdmin = app.admin.isAdmin()
        onPauseOrDispose { }
    }

    fun refresh() = scope.launch {
        refreshing = true
        refreshMsg = when (val r = app.repo.refresh()) {
            is SyncResult.Failed -> "Could not update: check your internet connection."
            else -> { app.prefs.lastSyncOk = System.currentTimeMillis(); null }
        }
        refreshing = false
    }
    LaunchedEffect(Unit) { if (feed.jobs.isEmpty()) refresh() }
    var showDisclaimer by remember { mutableStateOf(!app.prefs.disclaimerAccepted) }
    if (showDisclaimer) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { },
            icon = { Icon(Icons.Outlined.Info, null) },
            title = { Text("Before you use GovJob Radar") },
            text = { Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                com.dhruvaxtechnologies.govjobradar.ui.DisclaimerContent()
            } },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { app.prefs.disclaimerAccepted = true; showDisclaimer = false }) {
                    Text("I understand, I'll check the official notice")
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("GovJob Radar", fontWeight = FontWeight.Bold)
                        Text(updatedLabel(feed.generatedAt), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary, actionIconContentColor = MaterialTheme.colorScheme.onPrimary),
                actions = {
                    if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    else IconButton(onClick = { refresh() }) { Icon(Icons.Outlined.Refresh, "Update now") }
                    IconButton(onClick = { nav.navigate("search") }) { Icon(Icons.Outlined.Search, "Search") }
                    if (isAdmin) IconButton(onClick = { nav.navigate("admin") }) { Icon(Icons.Filled.AdminPanelSettings, "Admin") }
                    IconButton(onClick = { nav.navigate("settings") }) { Icon(Icons.Outlined.Settings, "Settings") }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                listOf(
                    Triple("central", "Central", Icons.Outlined.AccountBalance),
                    Triple("state", "State", Icons.Outlined.Map),
                    Triple("deputation", "Deputation", Icons.Outlined.SwapHoriz),
                    Triple("my", "My Jobs", Icons.Outlined.Bookmarks),
                ).forEach { (id, label, icon) ->
                    NavigationBarItem(selected = tab == id, onClick = { tab = id }, icon = { Icon(icon, null) }, label = { Text(label) })
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            Column {
                refreshMsg?.let { InfoBanner(it, MaterialTheme.colorScheme.error) { refreshMsg = null } }
                val unlocked by app.billing.unlocked.collectAsState()
                if (!unlocked && !isAdmin) {
                    val d = app.billing.trialDaysLeft()
                    // Deliberately quiet: small, light text.
                    Text(if (d > 0) "Free trial · $d day${if (d == 1) "" else "s"} left" else "Free trial ended · tap to unlock the full app",
                        Modifier.fillMaxWidth().clickable { nav.navigate("unlock") }.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (d > 0) 0.55f else 0.8f))
                }
                if (feed.jobs.isEmpty() && feed.generatedAt.isEmpty()) {
                    EmptyState(if (refreshing) "Getting the latest jobs…" else "No jobs downloaded yet",
                        "Jobs are updated every night from official websites.")
                } else when (tab) {
                    "central" -> CentralTab(nav, feed.jobs, profile)
                    "state" -> StateTab(nav, feed.jobs, profile.homeState)
                    "deputation" -> DeputationTab(nav, feed.jobs, app, saved)
                    else -> MyJobsTab(nav, app, saved, initialTab == "new")
                }
            }
        }
    }
}

private fun updatedLabel(generatedAt: String): String = runCatching {
    val h = Duration.between(Instant.parse(generatedAt), Instant.now()).toHours()
    when { h < 1 -> "Updated just now"; h < 24 -> "Updated $h h ago"; else -> "Updated ${h / 24} day(s) ago" }
}.getOrDefault("Not updated yet")

@Composable
private fun StatCard(label: String, value: Int, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(12.dp)) {
            Text("$value", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
private fun SectorGrid(sectors: List<Pair<Sector, Int>>, onClick: (Sector) -> Unit) {
    Column(Modifier.padding(horizontal = 10.dp)) {
        sectors.chunked(2).forEach { row ->
            Row {
                row.forEach { (s, n) ->
                    Card(onClick = { onClick(s) }, modifier = Modifier.weight(1f).padding(5.dp).alpha(if (n == 0) 0.5f else 1f),
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(2.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(40.dp).clip(CircleShape).background(s.tint.copy(alpha = 0.14f)),
                                    contentAlignment = Alignment.Center) {
                                    Icon(s.icon, null, tint = s.tint, modifier = Modifier.size(22.dp))
                                }
                                Spacer(Modifier.weight(1f))
                                Text("$n", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                                    color = if (n > 0) s.tint else MaterialTheme.colorScheme.outline)
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(s.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                                maxLines = 2, minLines = 2)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Colourful header at the top of a tab with the key counts. */
@Composable
private fun Hero(title: String, subtitle: String, stats: List<Triple<String, Int, () -> Unit>>) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().padding(14.dp).clip(RoundedCornerShape(22.dp))
        .background(Brush.linearGradient(listOf(cs.primary, Color(0xFF13808F), Color(0xFF2A9D8F))))) {
        Column(Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                stats.forEach { (label, value, onClick) ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.16f))
                        .clickable(onClick = onClick).padding(10.dp)) {
                        Text("$value", style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
                        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.9f), maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun CentralTab(nav: NavHostController, all: List<Job>, profile: com.dhruvaxtechnologies.govjobradar.data.Profile) {
    val jobs = Filters.select(all, "central", null, null, null)
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Hero("Central Government", "Ministries, defence, railways, research, banks and PSUs", listOf(
                Triple("Open now", jobs.count { !it.awaitingExtension }) { nav.navigate(listRoute("Central government jobs", level = "central")) },
                Triple("Closing in 7 days", Filters.apply(jobs, "closing", profile).size) { nav.navigate(listRoute("Closing soon", level = "central", filter = "closing")) },
                Triple("New", Filters.apply(jobs, "new", profile).size) { nav.navigate(listRoute("New this week", level = "central", filter = "new")) },
            ))
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(false, { nav.navigate(listRoute("Likely eligible for me", level = "central", filter = "eligible")) }, { Text("Eligible for me") }) }
                item { FilterChip(false, { nav.navigate(listRoute("Date extended", level = "central", filter = "extended")) }, { Text("Date extended") }) }
                item { FilterChip(false, { nav.navigate(listRoute("All central jobs", level = "central")) }, { Text("All jobs") }) }
            }
        }
        item { SectionTitle("Browse by sector") }
        item {
            val counts = Catalog.sectors.map { it to jobs.count { j -> j.sector == it.id } }
                .sortedByDescending { it.second }
            SectorGrid(counts) { s -> nav.navigate(listRoute(s.label, level = "central", sector = s.id)) }
        }
        item {
            InfoBanner(com.dhruvaxtechnologies.govjobradar.ui.Disclaimer.SHORT,
                MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun StateTab(nav: NavHostController, all: List<Job>, homeState: String?) {
    val stateJobs = Filters.select(all, "state", null, null, null)
    val counts = stateJobs.groupingBy { it.state ?: "" }.eachCount()
    var state by rememberSaveable { mutableStateOf(homeState ?: counts.maxByOrNull { it.value }?.key ?: Catalog.states.first()) }
    var menuOpen by remember { mutableStateOf(false) }
    val mine = stateJobs.filter { it.state == state }
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Hero(state, "State government vacancies", listOf(
                Triple("Open now", mine.count { !it.awaitingExtension }) { nav.navigate(listRoute("$state – all jobs", level = "state", state = state)) },
                Triple("Closing in 7 days", mine.count { (it.daysLeft() ?: 99) in 0..7 }) { nav.navigate(listRoute("$state – closing soon", level = "state", state = state, filter = "closing")) },
                Triple("New", mine.count { it.isNew() }) { nav.navigate(listRoute("$state – new", level = "state", state = state, filter = "new")) },
            ))
        }
        item {
            Box(Modifier.padding(12.dp)) {
                OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("$state  ·  ${mine.size} openings  ▾", fontWeight = FontWeight.SemiBold)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    Catalog.states.forEach { s ->
                        DropdownMenuItem(text = { Text("$s  (${counts[s] ?: 0})") }, onClick = { state = s; menuOpen = false })
                    }
                }
            }
        }
        item {
            OutlinedButton(onClick = { nav.navigate(listRoute("$state – all jobs", level = "state", state = state)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) { Text("See all ${mine.size} jobs in $state") }
        }
        item { SectionTitle("Browse by sector") }
        item {
            val sc = Catalog.sectors.map { it to mine.count { j -> j.sector == it.id } }.sortedByDescending { it.second }
            SectorGrid(sc) { s -> nav.navigate(listRoute("${s.label} · $state", level = "state", sector = s.id, state = state)) }
        }
        item { SectionTitle("Other states") }
        items(counts.entries.filter { it.key != state && it.key.isNotEmpty() }.sortedByDescending { it.value }) { (s, n) ->
            val rowMod = Modifier.fillMaxWidth().clickable { state = s }.padding(horizontal = 16.dp, vertical = 10.dp)
            Text("$s — $n openings", rowMod, color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeputationTab(nav: NavHostController, all: List<Job>, app: GovJobApp, saved: Set<String>) {
    val profile by app.prefs.profile.collectAsState()
    var level by rememberSaveable { mutableStateOf("central") }
    var band by rememberSaveable { mutableStateOf("all") }
    val dep = Filters.select(all, level, null, null, "deputation")
    val bands = listOf("all" to "All levels", "mine" to "Near my level", "1-6" to "Level 1–6", "7-10" to "Level 7–10",
        "11-13" to "Level 11–13", "14-18" to "Level 14+")
    val shown = Filters.sortByDeadline(dep.filter { j ->
        when (band) {
            "all" -> true
            "mine" -> Eligibility.payLevelOk(j, profile) == true
            else -> {
                val (lo, hi) = band.split("-").map { it.toInt() }
                val l = j.payLevelMin ?: return@filter false
                l in lo..hi
            }
        }
    })
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Hero("Deputation", "For serving government employees — apply through proper channel", listOf(
                Triple("Central", Filters.select(all, "central", null, null, "deputation").size) { level = "central" },
                Triple("State", Filters.select(all, "state", null, null, "deputation").size) { level = "state" },
                Triple("Near my level", dep.count { Eligibility.payLevelOk(it, profile) == true }) { band = "mine" },
            ))
        }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(12.dp)) {
                listOf("central" to "Central", "state" to "State").forEachIndexed { i, (id, label) ->
                    SegmentedButton(selected = level == id, onClick = { level = id }, shape = SegmentedButtonDefaults.itemShape(i, 2)) {
                        Text("$label (${Filters.select(all, id, null, null, "deputation").size})")
                    }
                }
            }
        }
        item {
            InfoBanner("Deputation posts are for serving government employees. Apply through proper channel — " +
                "usually needs vigilance clearance, integrity certificate and APAR copies of the last 5 years.",
                Color(0xFF6A1B9A))
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(bands.filter { it.first != "mine" || profile.payLevel != null }) { (id, label) ->
                    FilterChip(band == id, { band = id }, { Text(label) })
                }
            }
        }
        if (shown.isEmpty()) item { EmptyState("No deputation vacancies here right now", "New circulars are checked every night.") }
        items(shown, key = { it.id }) { j ->
            JobCard(j, profile, j.id in saved, { com.dhruvaxtechnologies.govjobradar.ui.toggleSaveGuarded(app, nav, j.id) }) {
                nav.navigate("job/${j.id}")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MyJobsTab(nav: NavHostController, app: GovJobApp, saved: Set<String>, startOnNew: Boolean) {
    val feed by app.repo.feed.collectAsState()
    val profile by app.prefs.profile.collectAsState()
    var seg by rememberSaveable { mutableStateOf(if (startOnNew) "new" else "saved") }
    val segs = listOf("saved" to "Saved", "eligible" to "Eligible", "new" to "New", "closed" to "Closed")
    val list = when (seg) {
        "saved" -> (feed.jobs + feed.archive).filter { it.id in saved }
        "eligible" -> Filters.apply(feed.jobs, "eligible", profile)
        "new" -> Filters.apply(feed.jobs, "new", profile)
        else -> feed.archive.filter { it.id in saved } + feed.archive.filter { it.id !in saved }
    }.let { if (seg == "closed") it else Filters.sortByDeadline(it) }
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(12.dp)) {
                segs.forEachIndexed { i, (id, label) ->
                    SegmentedButton(selected = seg == id, onClick = { seg = id }, shape = SegmentedButtonDefaults.itemShape(i, segs.size)) {
                        Text(label, maxLines = 1)
                    }
                }
            }
        }
        if (seg == "eligible" && profile.dob == null) item {
            InfoBanner("Add your date of birth and qualification in Settings › My profile to see jobs you are likely eligible for.",
                MaterialTheme.colorScheme.primary) { nav.navigate("profile") }
        }
        if (seg == "saved" && list.isNotEmpty()) item {
            Text("You get reminders 3 days and 1 day before each last date, and a note if a date is extended.",
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (list.isEmpty()) item {
            EmptyState(when (seg) {
                "saved" -> "No saved jobs yet"; "eligible" -> "No matches yet"; "new" -> "Nothing new today"; else -> "No closed jobs"
            }, if (seg == "saved") "Tap the bookmark on any job to save it." else "Check again tomorrow morning.")
        }
        items(list, key = { it.id }) { j ->
            JobCard(j, profile, j.id in saved, { com.dhruvaxtechnologies.govjobradar.ui.toggleSaveGuarded(app, nav, j.id) }) {
                nav.navigate("job/${j.id}")
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}
