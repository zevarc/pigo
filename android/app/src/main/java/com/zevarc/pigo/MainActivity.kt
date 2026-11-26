package com.zevarc.pigo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zevarc.pigo.ui.PiScreen
import com.zevarc.pigo.ui.PiViewModel
import com.zevarc.pigo.ui.SettingsScreen
import com.zevarc.pigo.ui.theme.PiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PiTheme {
                PiRoot()
            }
        }
    }
}

/**
 * Two screens and no navigation library: the settings form is a modal-ish
 * destination, and back simply flips the flag.
 */
@Composable
private fun PiRoot(viewModel: PiViewModel = viewModel()) {
    var showSettings by remember { mutableStateOf(viewModel.endpoint().isBlank()) }

    // Android throttles background sockets; on every return to the foreground the
    // client replays whatever the session produced while we were away.
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (!showSettings) viewModel.onForeground()
    }

    if (showSettings) {
        SettingsScreen(viewModel = viewModel, onBack = { showSettings = false })
    } else {
        PiScreen(viewModel = viewModel, onOpenSettings = { showSettings = true })
    }
}
