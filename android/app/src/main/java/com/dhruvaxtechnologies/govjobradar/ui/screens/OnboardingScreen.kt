package com.dhruvaxtechnologies.govjobradar.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dhruvaxtechnologies.govjobradar.GovJobApp
import com.dhruvaxtechnologies.govjobradar.data.Catalog
import com.dhruvaxtechnologies.govjobradar.sync.Scheduler
import com.dhruvaxtechnologies.govjobradar.ui.fmtDate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(editing: Boolean = false, onDone: () -> Unit) {
    val app = LocalContext.current.applicationContext as GovJobApp
    var p by remember { mutableStateOf(app.prefs.profile.value) }
    var pickDob by remember { mutableStateOf(false) }
    var stateMenu by remember { mutableStateOf(false) }
    var accepted by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Scaffold(topBar = {
        TopAppBar(title = { Text(if (editing) "My profile" else "Welcome to GovJob Radar") },
            navigationIcon = { if (editing) IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
            if (!editing) Text("Central, State and Deputation vacancies from official websites, updated every night. " +
                "Your details stay on this phone and are only used to show jobs that suit you.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(14.dp))
            Text("I am a…", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                ModeCard("Job seeker", "Open recruitment for the public", Icons.Outlined.School, !p.govtEmployee, Modifier.weight(1f)) {
                    p = p.copy(govtEmployee = false)
                }
                ModeCard("Government employee", "Also show deputation posts", Icons.Outlined.Badge, p.govtEmployee, Modifier.weight(1f)) {
                    p = p.copy(govtEmployee = true)
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Home state", style = MaterialTheme.typography.titleMedium)
            Box {
                OutlinedButton(onClick = { stateMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(p.homeState ?: "Choose your state ▾") }
                DropdownMenu(stateMenu, { stateMenu = false }) {
                    Catalog.states.forEach { s -> DropdownMenuItem({ Text(s) }, { p = p.copy(homeState = s); stateMenu = false }) }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Date of birth (for age limits)", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { pickDob = true }, modifier = Modifier.fillMaxWidth()) {
                Text(p.dob?.let { fmtDate(it.toString()) } ?: "Choose date")
            }

            Spacer(Modifier.height(16.dp))
            Text("Highest qualification", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Catalog.qualifications.forEach { (id, label) -> FilterChip(p.qualification == id, { p = p.copy(qualification = id) }, { Text(label) }) }
            }

            Spacer(Modifier.height(12.dp))
            Text("Category (for age relaxation)", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Catalog.categories.forEach { c -> FilterChip(p.category == c, { p = p.copy(category = c) }, { Text(c) }) }
            }
            ToggleRow("Person with disability (PwBD)", p.pwd) { p = p.copy(pwd = it) }
            ToggleRow("Ex-serviceman", p.exServiceman) { p = p.copy(exServiceman = it) }

            if (p.govtEmployee) {
                Spacer(Modifier.height(12.dp))
                Text("My current pay level: ${p.payLevel ?: "not set"}", style = MaterialTheme.typography.titleMedium)
                Slider(value = (p.payLevel ?: 6).toFloat(), onValueChange = { p = p.copy(payLevel = it.toInt()) },
                    valueRange = 1f..18f, steps = 16)
                Text("7th CPC pay matrix level. Used to show deputation posts near your level.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(16.dp))
            Text("Notifications", style = MaterialTheme.typography.titleMedium)
            ToggleRow("Morning summary of new jobs (8 AM)", p.notifyNew) { p = p.copy(notifyNew = it) }
            ToggleRow("Only jobs I'm likely eligible for", p.notifyOnlyEligible) { p = p.copy(notifyOnlyEligible = it) }
            ToggleRow("Update on Wi-Fi only", p.wifiOnly) { p = p.copy(wifiOnly = it) }

            Spacer(Modifier.height(20.dp))
            if (!editing) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Please read", fontWeight = FontWeight.Bold)
                        Text(com.dhruvaxtechnologies.govjobradar.ui.Disclaimer.FULL, style = MaterialTheme.typography.bodyMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(accepted, { accepted = it })
                            Text("I understand and will check the official notification before applying")
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            Button(enabled = editing || accepted, onClick = {
                val wifiChanged = p.wifiOnly != app.prefs.profile.value.wifiOnly
                app.prefs.saveProfile(p.copy(onboarded = true))
                if (accepted) app.prefs.disclaimerAccepted = true
                if (wifiChanged) Scheduler.scheduleNightly(app, p.wifiOnly)
                if (!editing && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                onDone()
            }, modifier = Modifier.fillMaxWidth()) { Text(if (editing) "Save" else "Start") }
            if (!editing) TextButton(enabled = accepted, onClick = { app.prefs.disclaimerAccepted = true; app.prefs.saveProfile(p.copy(onboarded = true)); onDone() },
                modifier = Modifier.fillMaxWidth()) { Text("Skip for now") }
        }
    }

    if (pickDob) {
        val st = rememberDatePickerState(
            initialSelectedDateMillis = (p.dob ?: LocalDate.now().minusYears(22)).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            yearRange = (LocalDate.now().year - 65)..(LocalDate.now().year - 14))
        DatePickerDialog(onDismissRequest = { pickDob = false },
            confirmButton = { TextButton(onClick = {
                st.selectedDateMillis?.let { p = p.copy(dob = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                pickDob = false
            }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { pickDob = false }) { Text("Cancel") } }) { DatePicker(st) }
    }
}

@Composable
private fun ModeCard(title: String, sub: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean,
                     modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier, colors = CardDefaults.cardColors(
        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Text(title, fontWeight = FontWeight.Bold)
            Text(sub, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(value, onChange)
    }
}
