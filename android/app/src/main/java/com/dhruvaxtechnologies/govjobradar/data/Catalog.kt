package com.dhruvaxtechnologies.govjobradar.data

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Engineering
import androidx.compose.material.icons.outlined.Factory
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material.icons.outlined.LocalPolice
import androidx.compose.material.icons.outlined.MarkunreadMailbox
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Train
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

data class Sector(val id: String, val label: String, val icon: ImageVector, val tint: Color)

object Catalog {
    val sectors = listOf(
        Sector("civil_services", "Administrative & Civil Services", Icons.Outlined.AccountBalance, Color(0xFF3949AB)),
        Sector("defence", "Defence", Icons.Outlined.Shield, Color(0xFF2E7D32)),
        Sector("police", "Police & Paramilitary", Icons.Outlined.LocalPolice, Color(0xFF283593)),
        Sector("space_research", "Space & Research", Icons.Outlined.RocketLaunch, Color(0xFF6A1B9A)),
        Sector("railways", "Railways", Icons.Outlined.Train, Color(0xFFC62828)),
        Sector("banking", "Banking & Finance", Icons.Outlined.Apartment, Color(0xFF00838F)),
        Sector("ssc", "SSC & Clerical", Icons.Outlined.Badge, Color(0xFF5D4037)),
        Sector("psu", "PSUs", Icons.Outlined.Factory, Color(0xFF455A64)),
        Sector("teaching", "Teaching & Education", Icons.Outlined.School, Color(0xFFEF6C00)),
        Sector("health", "Health & Medical", Icons.Outlined.LocalHospital, Color(0xFFD81B60)),
        Sector("engineering", "Engineering & Technical", Icons.Outlined.Engineering, Color(0xFF1565C0)),
        Sector("postal", "Postal & Telecom", Icons.Outlined.MarkunreadMailbox, Color(0xFFF9A825)),
        Sector("judiciary", "Judiciary & Legal", Icons.Outlined.Gavel, Color(0xFF4E342E)),
        Sector("other", "Other Departments", Icons.Outlined.MoreHoriz, Color(0xFF607D8B)),
    )
    private val byId = sectors.associateBy { it.id }
    fun sector(id: String): Sector = byId[id] ?: byId.getValue("other")

    val states = listOf(
        "Andhra Pradesh", "Arunachal Pradesh", "Assam", "Bihar", "Chhattisgarh", "Goa", "Gujarat", "Haryana",
        "Himachal Pradesh", "Jharkhand", "Karnataka", "Kerala", "Madhya Pradesh", "Maharashtra", "Manipur",
        "Meghalaya", "Mizoram", "Nagaland", "Odisha", "Punjab", "Rajasthan", "Sikkim", "Tamil Nadu", "Telangana",
        "Tripura", "Uttar Pradesh", "Uttarakhand", "West Bengal", "Andaman & Nicobar Islands", "Chandigarh",
        "Dadra & Nagar Haveli and Daman & Diu", "Delhi", "Jammu & Kashmir", "Ladakh", "Lakshadweep", "Puducherry",
    )

    /** Qualification ladder used for "Eligible for me". */
    val qualifications = listOf(
        "10th" to "10th pass", "12th" to "12th pass", "iti" to "ITI", "diploma" to "Diploma",
        "graduate" to "Graduate (any)", "engineering" to "Engineering graduate (B.E./B.Tech)",
        "medical" to "Medical (MBBS/BDS/Nursing)", "postgraduate" to "Post-graduate", "phd" to "PhD",
    )
    val categories = listOf("General", "EWS", "OBC", "SC", "ST")
}
