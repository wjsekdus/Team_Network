package com.nettwork.policethief.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.nettwork.policethief.presentation.game.EscortScreen
import com.nettwork.policethief.presentation.game.GameMapScreen
import com.nettwork.policethief.presentation.game.PowerSaveScreen
import com.nettwork.policethief.presentation.home.HomeScreen
import com.nettwork.policethief.presentation.login.LaunchScreen
import com.nettwork.policethief.presentation.login.LoginScreen
import com.nettwork.policethief.presentation.matchmaking.MatchmakingScreen
import com.nettwork.policethief.presentation.permission.PermissionScreen
import com.nettwork.policethief.presentation.prepare.RoleRevealScreen
import com.nettwork.policethief.presentation.prepare.ZoneConfirmScreen
import com.nettwork.policethief.presentation.qr.MyQrScreen
import com.nettwork.policethief.presentation.qr.QrScanScreen
import com.nettwork.policethief.presentation.result.GameResultScreen
import com.nettwork.policethief.presentation.settings.SettingsScreen
import com.nettwork.policethief.shared.model.PlayerRole

/**
 * 전체 화면 이동 그래프. 각 화면은 "다음에 어디로 갈지"를 콜백으로만 알리고,
 * 실제 navigate 호출은 이 파일에서만 한다(화면끼리 서로를 모르게 하기 위함).
 */
@Composable
fun AppNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = LaunchRoute) {
        composable<LaunchRoute> {
            LaunchScreen(
                onLoggedIn = { navController.navigate(HomeRoute) { popUpTo(LaunchRoute) { inclusive = true } } },
                onNeedLogin = { navController.navigate(LoginRoute) { popUpTo(LaunchRoute) { inclusive = true } } },
            )
        }
        composable<LoginRoute> {
            LoginScreen(
                onLoggedIn = { needsPermission ->
                    navController.navigate(HomeRoute) { popUpTo(LoginRoute) { inclusive = true } }
                    if (needsPermission) navController.navigate(PermissionRoute)
                },
            )
        }
        composable<PermissionRoute> {
            PermissionScreen(onDone = { navController.popBackStack() })
        }
        composable<HomeRoute> {
            HomeScreen(
                onStartMatchmaking = { navController.navigate(MatchmakingRoute) },
                onOpenSettings = { navController.navigate(SettingsRoute) },
                onOpenPermissions = { navController.navigate(PermissionRoute) },
            )
        }
        composable<MatchmakingRoute> {
            MatchmakingScreen(
                onMatched = { gameId -> navController.navigate(RoleRevealRoute(gameId)) { popUpTo(HomeRoute) } },
                onExit = { navController.popBackStack(HomeRoute, inclusive = false) },
                onOpenPermissions = { navController.navigate(PermissionRoute) },
            )
        }
        composable<RoleRevealRoute> { entry ->
            val gameId = entry.toRoute<RoleRevealRoute>().gameId
            RoleRevealScreen(
                gameId = gameId,
                onContinue = { navController.navigate(ZoneConfirmRoute(gameId)) { popUpTo(HomeRoute) } },
                onLeftGame = { navController.navigate(GameResultRoute(gameId)) { popUpTo(HomeRoute) } },
            )
        }
        composable<ZoneConfirmRoute> { entry ->
            val gameId = entry.toRoute<ZoneConfirmRoute>().gameId
            ZoneConfirmScreen(
                gameId = gameId,
                onGameStarted = { role ->
                    val target: Any = if (role == PlayerRole.POLICE) PoliceMapRoute(gameId) else ThiefMapRoute(gameId)
                    navController.navigate(target) { popUpTo(HomeRoute) }
                },
                onBackToMatchmaking = { navController.navigate(MatchmakingRoute) { popUpTo(HomeRoute) } },
                onLeftGame = { navController.navigate(GameResultRoute(gameId)) { popUpTo(HomeRoute) } },
            )
        }
        composable<PoliceMapRoute> { entry -> GameMapRoot(navController, entry.toRoute<PoliceMapRoute>().gameId, PlayerRole.POLICE) }
        composable<ThiefMapRoute> { entry -> GameMapRoot(navController, entry.toRoute<ThiefMapRoute>().gameId, PlayerRole.THIEF) }
        composable<QrScanRoute> { entry ->
            QrScanScreen(gameId = entry.toRoute<QrScanRoute>().gameId, onClose = { navController.popBackStack() })
        }
        composable<MyQrRoute> { entry ->
            MyQrScreen(gameId = entry.toRoute<MyQrRoute>().gameId, onClose = { navController.popBackStack() })
        }
        composable<EscortRoute> { entry ->
            EscortScreen(gameId = entry.toRoute<EscortRoute>().gameId, onClose = { navController.popBackStack() })
        }
        composable<PowerSaveRoute> { entry ->
            PowerSaveScreen(gameId = entry.toRoute<PowerSaveRoute>().gameId, onClose = { navController.popBackStack() })
        }
        composable<GameResultRoute> { entry ->
            GameResultScreen(
                gameId = entry.toRoute<GameResultRoute>().gameId,
                onGoHome = { navController.navigate(HomeRoute) { popUpTo(HomeRoute) { inclusive = true } } },
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onLoggedOut = { navController.navigate(LoginRoute) { popUpTo(0) { inclusive = true } } },
                onOpenPermissions = { navController.navigate(PermissionRoute) },
            )
        }
    }
}

@Composable
private fun GameMapRoot(navController: NavHostController, gameId: String, role: PlayerRole) {
    GameMapScreen(
        gameId = gameId,
        expectedRole = role,
        onOpenQrScan = { navController.navigate(QrScanRoute(gameId)) },
        onOpenMyQr = { navController.navigate(MyQrRoute(gameId)) },
        onOpenEscort = { navController.navigate(EscortRoute(gameId)) },
        onOpenPowerSave = { navController.navigate(PowerSaveRoute(gameId)) },
        onGameEnded = { navController.navigate(GameResultRoute(gameId)) { popUpTo(HomeRoute) } },
    )
}
