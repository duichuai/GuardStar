package com.guardstar.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.guardstar.app.ui.GuardStarApp
import com.guardstar.app.ui.theme.GuardStarTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as GuardStarApp
        setContent {
            GuardStarTheme {
                GuardStarApp(
                    session = app.session,
                    roleStore = app.roleStore,
                    authApi = app.authApi,
                    relationApi = app.relationApi,
                )
            }
        }
    }
}
