package com.aerospring.arworld

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aerospring.arworld.core.ui.theme.ArWorldTheme
import com.aerospring.arworld.feature.arbc.AR_BUSINESS_CARDS_ROUTE
import com.aerospring.arworld.feature.arbc.ArBcEntryPoint
import com.aerospring.arworld.feature.home.HomeScreen
import com.aerospring.arworld.feature.whereanythingis.WhereAnythingIsScreen
import com.aerospring.arworld.navigation.ArWorldDestinations

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ArWorldTheme {
                ArWorldNavHost()
            }
        }
    }
}

@Composable
private fun ArWorldNavHost(
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = ArWorldDestinations.HOME
    ) {
        composable(ArWorldDestinations.HOME) {
            HomeScreen(
                onTileClick = { route -> navController.navigate(route) }
            )
        }
        composable(ArWorldDestinations.WHERE_ANYTHING_IS) {
            WhereAnythingIsScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
        composable(AR_BUSINESS_CARDS_ROUTE) {
            ArBcEntryPoint(
                onExit = { navController.popBackStack() }
            )
        }
    }
}