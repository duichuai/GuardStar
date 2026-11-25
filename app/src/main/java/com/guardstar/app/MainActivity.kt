package com.guardstar.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.guardstar.app.service.Notifications
import com.guardstar.app.ui.GuardStarApp
import com.guardstar.app.ui.theme.GuardStarTheme

class MainActivity : ComponentActivity() {

    private val pendingEvent = mutableStateOf<Notifications.EventTarget?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        pendingEvent.value = Notifications.eventTarget(intent)
        val app = application as GuardStarApp
        setContent {
            GuardStarTheme {
                GuardStarApp(
                    session = app.session,
                    roleStore = app.roleStore,
                    sharingStore = app.sharingStore,
                    authApi = app.authApi,
                    relationApi = app.relationApi,
                    locationApi = app.locationApi,
                    locationRepository = app.locationRepository,
                    sosApi = app.sosApi,
                    eventApi = app.eventApi,
                    fenceApi = app.fenceApi,
                    pendingEvent = pendingEvent.value,
                    onPendingEventHandled = { pendingEvent.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingEvent.value = Notifications.eventTarget(intent)
    }
}
