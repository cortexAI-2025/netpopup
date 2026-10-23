package com.netpopup.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.netpopup.ui.screen.local.LocalChatScreen
import com.netpopup.ui.screen.privatechat.PrivateChatScreen
import com.netpopup.ui.screen.onboarding.OnboardingScreen
import com.netpopup.ui.screen.rooms.RoomsScreen
import com.netpopup.ui.screen.settings.SettingsScreen

/** Sealed route definitions — avoids magic strings scattered across the code. */
sealed class Screen(val route: String) {
    object LocalChat    : Screen("local_chat")
    object Rooms        : Screen("rooms")
    object Settings     : Screen("settings")
    object Onboarding   : Screen("onboarding")
    object PrivateChat  : Screen("private_chat/{roomId}") {
        fun createRoute(roomId: String) = "private_chat/$roomId"
    }
}

@Composable
fun NavGraph(startDestination: String = Screen.LocalChat.route) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {

        // 0. Premier lancement : modèle, pseudo, permissions
        composable(Screen.Onboarding.route) {
            OnboardingScreen(onDone = {
                navController.navigate(Screen.LocalChat.route) { popUpTo(Screen.Onboarding.route) { inclusive = true } }
            })
        }

        // Réglages
        composable(Screen.Settings.route) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }

        // 1. Local geo chatroom (default landing screen)
        composable(Screen.LocalChat.route) {
            LocalChatScreen(
                onNavigateToRooms = { navController.navigate(Screen.Rooms.route) },
                onNavigateToSettings = { navController.navigate(Screen.Settings.route) }
            )
        }

        // 2. Private rooms list / create / join
        composable(Screen.Rooms.route) {
            RoomsScreen(
                onBack = { navController.popBackStack() },
                onJoinRoom = { roomId ->
                    navController.navigate(Screen.PrivateChat.createRoute(roomId))
                }
            )
        }

        // 3. Private chat screen (one room or 1:1 — roomId is the conversation id)
        composable(
            route = Screen.PrivateChat.route,
            arguments = listOf(navArgument("roomId") { type = NavType.StringType })
        ) { backStackEntry ->
            val roomId = backStackEntry.arguments?.getString("roomId") ?: return@composable
            PrivateChatScreen(
                roomId = roomId,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
