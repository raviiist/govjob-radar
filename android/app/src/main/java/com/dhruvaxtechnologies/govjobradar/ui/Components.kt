package com.dhruvaxtechnologies.govjobradar.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dhruvaxtechnologies.govjobradar.data.Catalog
import com.dhruvaxtechnologies.govjobradar.data.Eligibility
import com.dhruvaxtechnologies.govjobradar.data.Job
import com.dhruvaxtechnologies.govjobradar.data.Profile
import com.dhruvaxtechnologies.govjobradar.ui.theme.Danger
import com.dhruvaxtechnologies.govjobradar.ui.theme.Ok
import com.dhruvaxtechnologies.govjobradar.ui.theme.Warn
import java.time.LocalDate
import java.time.format.DateTimeFormatter

val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")
fun fmtDate(iso: String?): String = iso?.let { runCatching { LocalDate.parse(it.take(10)).format(DATE_FMT) }.getOrNull() } ?: "—"

fun openUrl(context: Context, url: String?) {
    if (url.isNullOrBlank()) return
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
fun Badge(text: String, bg: Color, fg: Color = Color.White) {
    Text(text, color = fg, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp))
}

@Composable
fun SoftChip(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 7.dp, vertical = 3.dp))
}

@Composable
fun deadlineColor(days: Long?): Color = when {
    days == null -> MaterialTheme.colorScheme.onSurfaceVariant
    days < 0 -> MaterialTheme.colorScheme.onSurfaceVariant
    days <= 3 -> Danger
    days <= 7 -> Warn
    else -> Ok
}

fun deadlineText(job: Job): String {
    val d = job.daysLeft() ?: return job.lastDateNote?.let { "Last date: $it" } ?: "Last date: see notice"
    return when {
        job.awaitingExtension || d < 0 -> "Closed ${fmtDate(job.lastDate)}"
        d == 0L -> "Last date TODAY"
        d == 1L -> "Last date tomorrow"
        else -> "$d days left · ${fmtDate(job.lastDate)}"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JobCard(job: Job, profile: Profile, saved: Boolean, onSave: () -> Unit, onClick: () -> Unit) {
    val sector = Catalog.sector(job.sector)
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(2.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(sector.tint.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center) {
                    Icon(sector.icon, null, tint = sector.tint, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(job.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(job.organisation + (job.state?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onSave, modifier = Modifier.size(36.dp)) {
                    Icon(if (saved) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        contentDescription = if (saved) "Saved" else "Save", tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (job.isNew()) Badge("NEW", MaterialTheme.colorScheme.primary)
                if (job.extended) Badge("DATE EXTENDED", Warn)
                if (job.awaitingExtension) Badge("CHECKING EXTENSION", MaterialTheme.colorScheme.outline)
                if (job.isDeputation) Badge("DEPUTATION", Color(0xFF6A1B9A))
                if (Eligibility.likelyEligible(job, profile) == true) Badge("LIKELY ELIGIBLE", Ok)
                job.totalVacancies?.let { SoftChip("$it posts") }
                job.payLevelMin?.let { lo -> SoftChip(if (job.payLevelMax != null && job.payLevelMax != lo) "Level $lo–${job.payLevelMax}" else "Level $lo") }
            }
            Spacer(Modifier.height(10.dp))
            DeadlinePill(job)
        }
    }
}

@Composable
fun DeadlinePill(job: Job) {
    val c = deadlineColor(job.daysLeft())
    Row(Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Event, null, Modifier.size(14.dp), tint = c)
        Spacer(Modifier.width(6.dp))
        Text(deadlineText(job), color = c, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun EmptyState(title: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.Inbox, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp))
}

@Composable
fun InfoBanner(text: String, color: Color, onClick: (() -> Unit)? = null) {
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .let { if (onClick != null) it.clickable { onClick() } else it }) {
        Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}


/** Saving jobs (with reminders) is part of the full app; un-saving is always allowed. */
fun toggleSaveGuarded(app: com.dhruvaxtechnologies.govjobradar.GovJobApp, nav: androidx.navigation.NavHostController, id: String) {
    if (id !in app.prefs.saved.value && !app.fullAccess()) { nav.navigate("unlock"); return }
    app.prefs.toggleSaved(id)
    com.dhruvaxtechnologies.govjobradar.sync.Scheduler.syncReminders(app)
}

@Composable
fun LockedCard(nav: androidx.navigation.NavHostController, what: String) {
    Card(onClick = { nav.navigate("unlock") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(14.dp)) {
            Text("Free trial ended", fontWeight = FontWeight.Bold)
            Text("Unlock the full app to see $what. The official notification below is always free.",
                style = MaterialTheme.typography.bodyMedium)
            Text("Unlock →", Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}
