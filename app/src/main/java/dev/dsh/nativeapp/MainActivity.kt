package dev.dsh.nativeapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.dsh.nativeapp.data.ChatUiState
import dev.dsh.nativeapp.ui.ChatScaffold
import dev.dsh.nativeapp.ui.pairing.PairingScreen
import dev.dsh.nativeapp.ui.settings.SettingsScreen
import dev.dsh.nativeapp.ui.theme.AppearanceMode
import dev.dsh.nativeapp.ui.theme.DshTheme
import dev.dsh.nativeapp.vm.ChatViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as DshApplication

        setContent {
            var appearance by remember { mutableStateOf(AppearanceMode.System) }

            DshTheme(mode = appearance) {
                DshRoot(
                    app = app,
                    appearance = appearance,
                    onAppearance = { appearance = it },
                )
            }
        }
    }
}

@Composable
private fun DshRoot(
    app: DshApplication,
    appearance: AppearanceMode,
    onAppearance: (AppearanceMode) -> Unit,
) {
    var paired by remember { mutableStateOf(app.hasHost) }
    var pairingBusy by remember { mutableStateOf(false) }
    var pairingError by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var host by remember { mutableStateOf(app.currentHost) }
    val scope = rememberCoroutineScope()

    if (!paired) {
        PairingScreen(
            busy = pairingBusy,
            error = pairingError,
            onPair = { input ->
                pairingError = null
                pairingBusy = true
                scope.launch {
                    val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        app.hostStore.pair(input)
                    }
                    pairingBusy = false
                    when (result) {
                        is dev.dsh.nativeapp.data.PairResult.Success -> {
                            app.attune(result.host)
                            host = result.host
                            paired = true
                        }
                        is dev.dsh.nativeapp.data.PairResult.Failure -> {
                            pairingError = result.message
                        }
                    }
                }
            },
        )
        return
    }

    val vm: ChatViewModel = viewModel(factory = ChatViewModel.factory(app))
    val chat by vm.chat.collectAsState()
    val groups by vm.workspaces.collectAsState()
    val catalog by vm.catalog.collectAsState()

    if (showSettings) {
        SettingsScreen(
            host = host,
            appearance = appearance,
            onAppearance = onAppearance,
            onBack = { showSettings = false },
            onUnpair = {
                app.unpair()
                host = null
                paired = false
                showSettings = false
            },
        )
        return
    }

    val modelLabel = catalog?.default?.let { ref ->
        catalog?.groups
            ?.firstOrNull { it.id == ref.provider }
            ?.models?.firstOrNull { it.id == ref.model }?.name
            ?: ref.model
    }

    var showModelPicker by remember { mutableStateOf(false) }

    ChatScaffold(
        state = chat,
        groups = groups,
        modelLabel = modelLabel,
        onSelectSession = { vm.openSession(it) },
        onNewSession = { vm.newSession() },
        onOpenSettings = { showSettings = true },
        onSend = { vm.send(it) },
        onStop = { vm.stop() },
        onModelClick = { showModelPicker = true },
    )

    if (showModelPicker) {
        dev.dsh.nativeapp.ui.chat.ModelPickerSheet(
            catalog = catalog,
            currentProvider = catalog?.default?.provider,
            currentModel = catalog?.default?.model,
            onDismiss = { showModelPicker = false },
            onSelect = { provider, model, effort ->
                showModelPicker = false
                vm.selectModel(provider, model, effort)
            },
        )
    }
}
