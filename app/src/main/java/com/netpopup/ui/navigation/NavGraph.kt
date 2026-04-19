package com.netpopup.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.netpopup.ui.screen.local.LocalChatScreen
import com.netpopup.ui.screen.privatechat.PrivateChatScreen
import com.netpopup.ui.screen.rooms.RoomsScreen

/** Sealed route definitions — avoids magic strings scattered across the code. */
sealed class Screen(val route: String) {
    object LocalChat    : Screen("local_chat")
    object Rooms        : Screen("rooms")
    object PrivateChat  : Screen("private_chat/{roomId}") {
        fun createRoute(roomId: String) = "private_chat/$roomId"
    }
}

@Composable
fun NavGraph() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Screen.LocalChat.route
    ) {

        // 1. Local geo chatroom (default landing screen)
        composable(Screen.LocalChat.route) {
            LocalChatScreen(
                onNavigateToRooms = { navController.navigate(Screen.Rooms.route) }
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

        // 3. Private chat screen (one room)
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
