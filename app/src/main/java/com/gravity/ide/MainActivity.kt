package com.gravity.ide

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.gravity.ide.data.settings.AppSettings
import com.gravity.ide.ui.GravityRoot
import com.gravity.ide.ui.LocalAppContainer
import com.gravity.ide.ui.theme.GravityTheme

class MainActivity : ComponentActivity() {

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as GravityApp).container
        // Opened from a "reply ready" notification: show that chat.
        intent?.getStringExtra("open_chat")?.let { com.gravity.ide.agent.ChatRuns.openChat.value = it }
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            val settings by container.settings.settings.collectAsState(initial = AppSettings())
            CompositionLocalProvider(LocalAppContainer provides container) {
                GravityTheme(settings.theme) { GravityRoot() }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra("open_chat")?.let { com.gravity.ide.agent.ChatRuns.openChat.value = it }
    }

    override fun onStart() {
        super.onStart()
        com.gravity.ide.agent.ChatRuns.Visibility.onScreen = true
        (application as GravityApp).container.chatRuns.refreshService()
    }

    override fun onStop() {
        // Leaving Gravity while a reply is being written: the foreground service keeps it going.
        com.gravity.ide.agent.ChatRuns.Visibility.onScreen = false
        (application as GravityApp).container.chatRuns.refreshService()
        super.onStop()
    }
}
