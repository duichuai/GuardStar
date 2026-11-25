package com.guardstar.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.guardstar.app.core.role.RoleStore
import com.guardstar.app.core.role.UserRole
import com.guardstar.app.core.session.SessionStore
import com.guardstar.app.data.auth.AuthApi
import com.guardstar.app.data.event.EventApi
import com.guardstar.app.data.fence.FenceApi
import com.guardstar.app.data.location.LocationApi
import com.guardstar.app.data.location.LocationRepository
import com.guardstar.app.data.relation.RelationApi
import com.guardstar.app.data.sos.SosApi
import com.guardstar.app.service.Notifications
import com.guardstar.app.service.WardLocationService
import com.guardstar.app.ui.guardian.GuardianHomeScreen
import com.guardstar.app.ui.guardian.GuardianMapScreen
import com.guardstar.app.ui.guardian.FenceScreen
import com.guardstar.app.ui.guardian.TrackScreen
import com.guardstar.app.ui.login.LoginScreen
import com.guardstar.app.ui.role.RoleSelectScreen
import com.guardstar.app.ui.ward.WardHomeScreen

private const val ROUTE_ROLE = "role"
private const val ROUTE_LOGIN = "login"
private const val ROUTE_WARD = "ward_home"
private const val ROUTE_GUARDIAN = "guardian_home"
private const val ROUTE_GUARDIAN_MAP = "guardian_map/{relationId}/{wardDeviceId}/{wardNickname}"
private const val ROUTE_GUARDIAN_FENCES = "guardian_fences/{relationId}/{wardDeviceId}/{wardNickname}"
private const val ROUTE_GUARDIAN_TRACK = "guardian_track/{relationId}/{wardNickname}"

@Composable
fun GuardStarApp(
    session: SessionStore,
    roleStore: RoleStore,
    authApi: AuthApi,
    relationApi: RelationApi,
    locationApi: LocationApi,
    locationRepository: LocationRepository,
    sosApi: SosApi,
    eventApi: EventApi,
    fenceApi: FenceApi,
    pendingEvent: Notifications.EventTarget?,
    onPendingEventHandled: () -> Unit,
) {
    val navController = rememberNavController()
    val context = LocalContext.current
    var role by remember { mutableStateOf(roleStore.loadRole()) }

    val startDestination = when {
        !session.isLoggedIn() -> ROUTE_ROLE
        role == UserRole.WARD -> ROUTE_WARD
        role == UserRole.GUARDIAN -> ROUTE_GUARDIAN
        else -> ROUTE_ROLE
    }

    fun goHome(target: UserRole) {
        navController.navigate(if (target == UserRole.WARD) ROUTE_WARD else ROUTE_GUARDIAN) {
            popUpTo(0) { inclusive = true }
        }
    }

    fun logout() {
        // 退出登录时停止被守护端定位服务，避免带着失效凭证持续重传。
        runCatching { context.stopService(Intent(context, WardLocationService::class.java)) }
        session.clear()
        navController.navigate(ROUTE_ROLE) {
            popUpTo(0) { inclusive = true }
        }
    }

    LaunchedEffect(pendingEvent) {
        if (pendingEvent == null) return@LaunchedEffect
        if (session.isLoggedIn() && role == UserRole.GUARDIAN) {
            navController.navigate(ROUTE_GUARDIAN) { launchSingleTop = true }
        } else {
            onPendingEventHandled()
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(ROUTE_ROLE) {
            RoleSelectScreen(
                onRoleSelected = { selected ->
                    roleStore.saveRole(selected)
                    role = selected
                    navController.navigate(ROUTE_LOGIN)
                }
            )
        }
        composable(ROUTE_LOGIN) {
            val currentRole = role ?: UserRole.WARD
            LoginScreen(
                role = currentRole,
                authApi = authApi,
                onLoggedIn = { goHome(currentRole) },
                onBack = { navController.popBackStack() },
            )
        }
        composable(ROUTE_WARD) {
            WardHomeScreen(
                relationApi = relationApi,
                locationRepository = locationRepository,
                sosApi = sosApi,
                onLogout = { logout() },
            )
        }
        composable(ROUTE_GUARDIAN) {
            GuardianHomeScreen(
                relationApi = relationApi,
                eventApi = eventApi,
                sosApi = sosApi,
                fenceApi = fenceApi,
                locationApi = locationApi,
                onSelectRelation = { relation ->
                    val nickname = Uri.encode(relation.peerNickname.ifBlank { "被守护人" })
                    navController.navigate(
                        "guardian_map/${relation.relationId}/${relation.wardDeviceId}/$nickname"
                    )
                },
                onManageFences = { relation ->
                    val nickname = Uri.encode(relation.peerNickname.ifBlank { "被守护人" })
                    navController.navigate("guardian_fences/${relation.relationId}/${relation.wardDeviceId}/$nickname")
                },
                onViewTrack = { relation ->
                    val nickname = Uri.encode(relation.peerNickname.ifBlank { "被守护人" })
                    navController.navigate("guardian_track/${relation.relationId}/$nickname")
                },
                pendingEvent = pendingEvent,
                onPendingEventHandled = onPendingEventHandled,
                onLogout = { logout() },
            )
        }
        composable(
            route = ROUTE_GUARDIAN_MAP,
            arguments = listOf(
                navArgument("relationId") { type = NavType.StringType },
                navArgument("wardDeviceId") { type = NavType.StringType },
                navArgument("wardNickname") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            GuardianMapScreen(
                relationId = backStackEntry.arguments?.getString("relationId").orEmpty(),
                wardNickname = Uri.decode(backStackEntry.arguments?.getString("wardNickname")).orEmpty(),
                deviceId = backStackEntry.arguments?.getString("wardDeviceId").orEmpty(),
                locationApi = locationApi,
                fenceApi = fenceApi,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = ROUTE_GUARDIAN_FENCES,
            arguments = listOf(
                navArgument("relationId") { type = NavType.StringType },
                navArgument("wardDeviceId") { type = NavType.StringType },
                navArgument("wardNickname") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            FenceScreen(
                relationId = backStackEntry.arguments?.getString("relationId").orEmpty(),
                wardNickname = Uri.decode(backStackEntry.arguments?.getString("wardNickname")).orEmpty(),
                wardDeviceId = backStackEntry.arguments?.getString("wardDeviceId").orEmpty(),
                fenceApi = fenceApi,
                locationApi = locationApi,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = ROUTE_GUARDIAN_TRACK,
            arguments = listOf(
                navArgument("relationId") { type = NavType.StringType },
                navArgument("wardNickname") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            TrackScreen(
                relationId = backStackEntry.arguments?.getString("relationId").orEmpty(),
                wardNickname = Uri.decode(backStackEntry.arguments?.getString("wardNickname")).orEmpty(),
                locationApi = locationApi,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
