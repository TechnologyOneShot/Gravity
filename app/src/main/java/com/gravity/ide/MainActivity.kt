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
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            val settings by container.settings.settings.collectAsState(initial = AppSettings())
            CompositionLocalProvider(LocalAppContainer provides container) {
                GravityTheme(settings.theme) { GravityRoot() }
            }
        }
    }
}
