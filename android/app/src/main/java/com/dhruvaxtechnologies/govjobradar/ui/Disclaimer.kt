package com.dhruvaxtechnologies.govjobradar.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** One wording everywhere, so the app, store listing and website say the same thing. */
object Disclaimer {
    const val SHORT = "Not a government app. Every listing is checked against its official notice, but we cannot " +
        "guarantee 100% correctness — always check the official notification before applying."

    data class Point(val icon: ImageVector, val title: String, val text: String)

    val points = listOf(
        Point(Icons.Outlined.AccountBalance, "Not a government app",
            "GovJob Radar is an independent app by DhruvaX Technologies. It is not affiliated with or endorsed by any " +
                "government, ministry, department or recruiting body."),
        Point(Icons.Outlined.AutoAwesome, "Collected automatically",
            "Vacancies are gathered every night from official websites and summarised with the help of AI. Each one " +
                "is checked against its official notice and links to it."),
        Point(Icons.Outlined.FactCheck, "Always cross-check",
            "Mistakes, delays or missing vacancies can still happen, and dates or posts can change. Read the official " +
                "notification fully and confirm eligibility, last date, fee and how to apply before you apply."),
        Point(Icons.Outlined.Policy, "We cannot guarantee 100% correctness",
            "Always check the official notification — it is the only authoritative source. We are not responsible " +
                "for any loss from relying on the information in this app."),
    )
}

@Composable
fun DisclaimerContent(modifier: Modifier = Modifier) {
    Column(modifier) {
        Disclaimer.points.forEach { p ->
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                Icon(p.icon, null, Modifier.size(22.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(p.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    Text(p.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
