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

    // Resolve the ViewModel before any early return. Composing it conditionally
    // destroys and recreates it on every settings visit, which replays the whole
    // session from the host — the reason entering settings looked like a reload.
    val vm: ChatViewModel = viewModel(factory = ChatViewModel.factory(app))
    val chat by vm.chat.collectAsState()
    val groups by vm.workspaces.collectAsState()
    val catalog by vm.catalog.collectAsState()

    var showModelPicker by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }

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

    // Prefer the session's own selection; the catalog default only applies before
    // the first snapshot arrives.
    val activeProvider = chat.modelProvider ?: catalog?.default?.provider
    val activeModel = chat.modelName ?: catalog?.default?.model
    val activeEffort = chat.reasoningEffort ?: catalog?.default?.reasoningEffort

    val modelLabel = catalog?.groups
        ?.firstOrNull { it.id == activeProvider }
        ?.models?.firstOrNull { it.id == activeModel }
        ?.name
        ?: activeModel

    val effortSuffix = activeEffort
        ?.takeIf { it.isNotBlank() && it != "off" }
        ?.let { " · ${dev.dsh.nativeapp.ui.chat.effortLabel(it)}" }
        .orEmpty()

    ChatScaffold(
        state = chat,
        groups = groups,
        modelLabel = modelLabel?.let { "$it$effortSuffix" },
        onSelectSession = { vm.openSession(it) },
        onNewSession = { vm.newSession() },
        onOpenSettings = { showSettings = true },
        onSend = { vm.send(it) },
        onStop = { vm.stop() },
        onModelClick = { showModelPicker = true },
        onDismissError = { vm.dismissError() },
        onAllow = { vm.answerApproval(true) },
        onReject = { vm.answerApproval(false) },
        onRunCommand = { vm.runCommand(it) },
        draft = draft,
        onDraftChange = { draft = it },
    )

    if (showModelPicker) {
        dev.dsh.nativeapp.ui.chat.ModelPickerSheet(
            catalog = catalog,
            currentProvider = activeProvider,
            currentModel = activeModel,
            currentEffort = activeEffort,
            onDismiss = { showModelPicker = false },
            onSelect = { provider, model, effort ->
                showModelPicker = false
                vm.selectModel(provider, model, effort)
            },
        )
    }
}
