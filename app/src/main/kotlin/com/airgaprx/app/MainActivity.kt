package com.airgaprx.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.airgaprx.app.platform.EgressGuard
import com.airgaprx.app.ui.capture.CaptureScreen
import com.airgaprx.app.ui.kb.AboutScreen
import com.airgaprx.app.ui.results.AlertDetailScreen
import com.airgaprx.app.ui.results.ResultsScreen
import com.airgaprx.app.ui.review.ReviewScreen
import com.airgaprx.app.ui.shelf.ShelfScreen
import com.airgaprx.app.ui.theme.AirGapTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as AirGapApp).container
        val egress = EgressGuard.check(this)

        setContent {
            AirGapTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (!egress.ok) {
                        IntegrityFailure(egress.violations) // fail closed (R1)
                    } else {
                        LaunchedEffect(Unit) {
                            container.kbRepository.load()
                            container.medicationRepository.refresh()
                        }
                        AppNav()
                    }
                }
            }
        }
    }
}

private object Routes {
    const val SHELF = "shelf"
    const val CAPTURE = "capture"
    const val REVIEW = "review"
    const val RESULTS = "results"
    const val ALERT = "alert/{index}"
    const val ABOUT = "about"
}

/**
 * Navigation graph; mirrors the M1 state machine (Figure 7):
 * Shelf -> Capturing (self-loop on retake) -> Reviewing/Disambiguating -> Shelf -> Evaluating -> Results -> Detail.
 */
@Composable
private fun AppNav() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = Routes.SHELF) {
        composable(Routes.SHELF) {
            ShelfScreen(
                egressOk = true,
                onScan = { nav.navigate(Routes.CAPTURE) },
                onCheck = { nav.navigate(Routes.RESULTS) },
                onAbout = { nav.navigate(Routes.ABOUT) },
            )
        }
        composable(Routes.CAPTURE) {
            CaptureScreen(
                onBack = { nav.popBackStack() },
                onRecognised = { nav.navigate(Routes.REVIEW) },
                onManualAdd = { nav.navigate(Routes.REVIEW) },
            )
        }
        composable(Routes.REVIEW) {
            ReviewScreen(
                onBack = { nav.popBackStack() },
                onSaved = { nav.popBackStack(Routes.SHELF, inclusive = false) },
            )
        }
        composable(Routes.RESULTS) {
            ResultsScreen(onBack = { nav.popBackStack() }, onOpenAlert = { nav.navigate("alert/$it") })
        }
        composable(Routes.ALERT, arguments = listOf(navArgument("index") { type = NavType.IntType })) { entry ->
            AlertDetailScreen(entry.arguments?.getInt("index") ?: 0, onBack = { nav.popBackStack() })
        }
        composable(Routes.ABOUT) { AboutScreen(onBack = { nav.popBackStack() }) }
    }
}

@Composable
private fun IntegrityFailure(violations: List<String>) {
    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("This build is not trusted", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
        Text("It declares network permissions (${violations.joinToString()}), which breaks the app's offline guarantee. It will not run.")
    }
}
