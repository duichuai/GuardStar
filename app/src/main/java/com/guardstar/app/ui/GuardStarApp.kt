package com.guardstar.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.guardstar.app.core.role.RoleStore
import com.guardstar.app.core.role.UserRole
import com.guardstar.app.core.session.SessionStore
import com.guardstar.app.data.auth.AuthApi
import com.guardstar.app.data.relation.RelationApi
import com.guardstar.app.ui.guardian.GuardianHomeScreen
import com.guardstar.app.ui.login.LoginScreen
import com.guardstar.app.ui.role.RoleSelectScreen
import com.guardstar.app.ui.ward.WardHomeScreen

private const val ROUTE_ROLE = "role"
private const val ROUTE_LOGIN = "login"
private const val ROUTE_WARD = "ward_home"
private const val ROUTE_GUARDIAN = "guardian_home"

@Composable
fun GuardStarApp(
    session: SessionStore,
    roleStore: RoleStore,
    authApi: AuthApi,
    relationApi: RelationApi,
) {
    val navController = rememberNavController()
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
        session.clear()
        navController.navigate(ROUTE_ROLE) {
            popUpTo(0) { inclusive = true }
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
                onLogout = { logout() },
            )
        }
        composable(ROUTE_GUARDIAN) {
            GuardianHomeScreen(
                relationApi = relationApi,
                onLogout = { logout() },
            )
        }
    }
}
