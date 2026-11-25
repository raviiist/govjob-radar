package com.dhruvaxtechnologies.govjobradar.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.dhruvaxtechnologies.govjobradar.GovJobApp
import com.dhruvaxtechnologies.govjobradar.data.Job
import com.dhruvaxtechnologies.govjobradar.sync.Scheduler
import com.dhruvaxtechnologies.govjobradar.ui.EmptyState
import com.dhruvaxtechnologies.govjobradar.ui.JobCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobListScreen(nav: NavHostController, title: String, level: String?, sector: String?, state: String?, kind: String?,
                  filter: String?) {
    val app = LocalContext.current.applicationContext as GovJobApp
    val feed by app.repo.feed.collectAsState()
    val profile by app.prefs.profile.collectAsState()
    val saved by app.prefs.saved.collectAsState()
    var f by rememberSaveable { mutableStateOf(filter ?: "all") }
    var sort by rememberSaveable { mutableStateOf("deadline") }
    val base = Filters.select(feed.jobs, level, sector, state, kind)
    val list = Filters.apply(base, f, profile).let { l ->
        if (sort == "deadline") Filters.sortByDeadline(l) else l.sortedByDescending { it.firstSeen ?: "" }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(title, maxLines = 1) },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("all" to "All (${base.size})", "eligible" to "Eligible for me", "closing" to "Closing soon",
                        "new" to "New", "extended" to "Extended")) { (id, label) ->
                        FilterChip(f == id, { f = id }, { Text(label) })
                    }
                    item { FilterChip(sort == "newest", { sort = if (sort == "newest") "deadline" else "newest" },
                        { Text(if (sort == "newest") "Sort: newest" else "Sort: last date") }) }
                }
            }
            if (list.isEmpty()) item { EmptyState("No openings right now", "This list is checked again every night.") }
            items(list, key = { it.id }) { j -> Card(nav, app, j, profile, saved) }
        }
    }
}

@Composable
private fun Card(nav: NavHostController, app: GovJobApp, j: Job, profile: com.dhruvaxtechnologies.govjobradar.data.Profile, saved: Set<String>) {
    JobCard(j, profile, j.id in saved, { app.prefs.toggleSaved(j.id); Scheduler.syncReminders(app) }) { nav.navigate("job/${j.id}") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(nav: NavHostController) {
    val app = LocalContext.current.applicationContext as GovJobApp
    val feed by app.repo.feed.collectAsState()
    val profile by app.prefs.profile.collectAsState()
    val saved by app.prefs.saved.collectAsState()
    var q by rememberSaveable { mutableStateOf("") }
    val words = q.lowercase().split(" ").filter { it.length >= 2 }
    val results = if (words.isEmpty()) emptyList() else feed.jobs.filter { j ->
        val hay = listOf(j.title, j.organisation, j.sourceName, j.state, j.advtNo, j.location, j.qualificationSummary,
            j.posts.joinToString { it.name }).joinToString(" ").lowercase()
        words.all { it in hay }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Search") },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        LazyColumn(Modifier.padding(pad)) {
            item {
                OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(12.dp), singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    placeholder = { Text("Post, organisation, state, qualification…") })
            }
            if (words.isNotEmpty() && results.isEmpty()) item { EmptyState("No match", "Try a shorter word, e.g. \"clerk\" or \"ISRO\".") }
            if (words.isNotEmpty()) item {
                Text("${results.size} result(s)", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall)
            }
            items(Filters.sortByDeadline(results), key = { it.id }) { j -> Card(nav, app, j, profile, saved) }
        }
    }
}
