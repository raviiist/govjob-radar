package com.dhruvaxtechnologies.govjobradar.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dhruvaxtechnologies.govjobradar.GovJobApp
import com.dhruvaxtechnologies.govjobradar.sync.Notifier
import com.dhruvaxtechnologies.govjobradar.ui.admin.AdminScreen
import com.dhruvaxtechnologies.govjobradar.ui.screens.HomeScreen
import com.dhruvaxtechnologies.govjobradar.ui.screens.JobDetailScreen
import com.dhruvaxtechnologies.govjobradar.ui.screens.JobListScreen
import com.dhruvaxtechnologies.govjobradar.ui.screens.OnboardingScreen
import com.dhruvaxtechnologies.govjobradar.ui.screens.SearchScreen
import com.dhruvaxtechnologies.govjobradar.ui.screens.SettingsScreen
import com.dhruvaxtechnologies.govjobradar.ui.theme.GovJobTheme

class MainActivity : ComponentActivity() {
    private val pendingJob = mutableStateOf<String?>(null)
    private val pendingTab = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readIntent(intent)
        val app = application as GovJobApp
        setContent {
            GovJobTheme {
                val nav = rememberNavController()
                val profile by app.prefs.profile.collectAsState()
                AppNav(nav, start = if (profile.onboarded) "home/central" else "onboarding")
                val job by pendingJob
                val tab by pendingTab
                LaunchedEffect(job, tab, profile.onboarded) {
                    if (!profile.onboarded) return@LaunchedEffect
                    job?.let { nav.navigate("job/$it"); pendingJob.value = null }
                    tab?.let { nav.navigate("home/$it") { launchSingleTop = true }; pendingTab.value = null }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as GovJobApp).healthCheckOnOpen()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    private fun readIntent(i: Intent?) {
        i?.getStringExtra(Notifier.EXTRA_JOB)?.let { pendingJob.value = it }
        i?.getStringExtra(Notifier.EXTRA_TAB)?.let { if (pendingJob.value == null) pendingTab.value = it }
    }
}

fun listRoute(title: String, level: String? = null, sector: String? = null, state: String? = null,
              kind: String? = null, filter: String? = null): String =
    "list?title=${Uri.encode(title)}&level=${level ?: ""}&sector=${sector ?: ""}&state=${Uri.encode(state ?: "")}" +
        "&kind=${kind ?: ""}&filter=${filter ?: ""}"

@Composable
fun AppNav(nav: NavHostController, start: String) {
    NavHost(nav, startDestination = start) {
        composable("onboarding") {
            OnboardingScreen(onDone = { nav.navigate("home/central") { popUpTo("onboarding") { inclusive = true } } })
        }
        composable("profile") { OnboardingScreen(editing = true, onDone = { nav.popBackStack() }) }
        composable("home/{tab}", arguments = listOf(navArgument("tab") { type = NavType.StringType })) {
            HomeScreen(nav, initialTab = it.arguments?.getString("tab") ?: "central")
        }
        composable(
            "list?title={title}&level={level}&sector={sector}&state={state}&kind={kind}&filter={filter}",
            arguments = listOf("title", "level", "sector", "state", "kind", "filter").map { n ->
                navArgument(n) { type = NavType.StringType; defaultValue = "" }
            },
        ) { e ->
            val a = e.arguments!!
            fun s(k: String) = a.getString(k)?.takeIf { it.isNotBlank() }
            JobListScreen(nav, s("title") ?: "Jobs", s("level"), s("sector"), s("state"), s("kind"), s("filter"))
        }
        composable("job/{id}") { JobDetailScreen(nav, it.arguments?.getString("id") ?: "") }
        composable("search") { SearchScreen(nav) }
        composable("settings") { SettingsScreen(nav) }
        composable("admin") { AdminScreen(nav) }
    }
}
