package dev.dsh.nativeapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.dsh.nativeapp.data.ChatUiState
import dev.dsh.nativeapp.ui.ChatScaffold
import dev.dsh.nativeapp.ui.pairing.PairingScreen
import dev.dsh.nativeapp.ui.settings.SettingsScreen
import dev.dsh.nativeapp.ui.theme.AppearanceMode
import dev.dsh.nativeapp.ui.theme.DshTheme
import dev.dsh.nativeapp.ui.theme.LocalDshColors
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
    var showInfo by remember { mutableStateOf(false) }
    var showPermission by remember { mutableStateOf(false) }
    var showPlusMenu by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val permissions by vm.permissions.collectAsState()
    val attachments by vm.attachments.collectAsState()

    // The launcher callback is not an Activity member scope, so the context is
    // captured once as a local value.
    val pickerContext = LocalContext.current

    // Image picker and generic file picker, both scoped to the current Activity.
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        uri?.let { vm.addAttachment(displayNameOf(pickerContext, it), readBytesOf(pickerContext, it)) }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        uri?.let { vm.addAttachment(displayNameOf(pickerContext, it), readBytesOf(pickerContext, it)) }
    }

    // Settings is a screen, not a destination the Activity should exit from:
    // back has to return to the transcript.
    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler(enabled = showInfo) { showInfo = false }
    BackHandler(enabled = showPermission) { showPermission = false }
    BackHandler(enabled = showModelPicker) { showModelPicker = false }
    BackHandler(enabled = showPlusMenu) { showPlusMenu = false }

    // Settings is drawn over the transcript rather than replacing it. An early
    // `return` tore down ChatScreen, which discarded its LazyListState and its
    // follow-the-tail flag — that is why coming back landed on a stale offset
    // instead of the newest message.
    // Selection handles and the floating toolbar live in a Compose popup window
    // that always draws above Activity content, so they would float over the
    // settings overlay or the drawer. Selection is disabled while any overlay is
    // open; the transcript itself stays selectable.
    val overlayOpen = showSettings || showPlusMenu || showPermission || showInfo || showModelPicker

    androidx.compose.runtime.CompositionLocalProvider(
        dev.dsh.nativeapp.ui.chat.LocalTextSelectable provides !overlayOpen,
    ) {
        Box(Modifier.fillMaxSize()) {
    // Prefer the session's own selection; the catalog default only applies before
    // the first snapshot arrives.
    val activeProvider = chat.modelProvider ?: catalog?.default?.provider
    val activeModel = chat.modelName ?: catalog?.default?.model
    val activeEffort = chat.reasoningEffort ?: catalog?.default?.reasoningEffort

    // Watches are ~200dp wide: the chip keeps only the bare model id and the
    // effort as a short suffix, because "workbuddy/deepseek-v4.1-flash · 高"
    // alone overflows the composer row.
    val shortModel = activeModel?.substringAfterLast('/') ?: activeModel
    val shortEffort = activeEffort?.takeIf { it.isNotBlank() && it != "off" }
        ?.let { dev.dsh.nativeapp.ui.chat.effortShort(it) }
    val modelLabel = listOfNotNull(shortModel, shortEffort).joinToString(" ")

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
        onDismissError = { vm.dismissError() },
        onAllow = { vm.answerApproval(true) },
        onReject = { vm.answerApproval(false) },
        onRunCommand = { vm.runCommand(it) },
        draft = draft,
        onDraftChange = { draft = it },
        onInfo = { showInfo = true },
        onBranch = { seq -> vm.branchFrom(seq) },
        onPermissionClick = { showPermission = true },
        onAnswerQuestions = { callId, answers -> vm.answerQuestions(callId, answers) },
        onPlusClick = { showPlusMenu = true },
        attachmentNames = attachments.map { it.first },
        onRemoveAttachment = { vm.removeAttachment(it) },
        onDrawerOpen = { vm.refreshIndexNow() },
    )

    if (showPlusMenu) {
        dev.dsh.nativeapp.ui.chat.ComposerActionSheet(
            onPick = { action ->
                showPlusMenu = false
                when (action) {
                    dev.dsh.nativeapp.ui.chat.ComposerAction.PickImage -> imagePicker.launch("image/*")
                    dev.dsh.nativeapp.ui.chat.ComposerAction.PickFile -> filePicker.launch("*/*")
                    dev.dsh.nativeapp.ui.chat.ComposerAction.SlashCommand -> draft = "/"
                }
            },
            onDismiss = { showPlusMenu = false },
        )
    }

    if (showPermission) {
        dev.dsh.nativeapp.ui.chat.PermissionSheet(
            options = permissions.ifEmpty {
                listOf("read-only", "workspace-write", "danger-full-access")
            },
            current = chat.permissionPreset,
            onPick = { preset ->
                showPermission = false
                vm.setPermission(preset)
            },
            onDismiss = { showPermission = false },
        )
    }

    if (showInfo) {
        dev.dsh.nativeapp.ui.chat.SessionInfoSheet(
            state = chat,
            modelLabel = modelLabel,
            onDismiss = { showInfo = false },
        )
    }

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

        // Settings overlays the transcript so ChatScreen keeps its list state.
        // It is animated in and out: an instant swap read as a dropped frame.
        androidx.compose.animation.AnimatedVisibility(
            visible = showSettings,
            enter = androidx.compose.animation.fadeIn(
                animationSpec = androidx.compose.animation.core.tween(160),
            ) + androidx.compose.animation.slideInHorizontally(
                animationSpec = androidx.compose.animation.core.tween(200),
                initialOffsetX = { it / 6 },
            ),
            exit = androidx.compose.animation.fadeOut(
                animationSpec = androidx.compose.animation.core.tween(140),
            ) + androidx.compose.animation.slideOutHorizontally(
                animationSpec = androidx.compose.animation.core.tween(180),
                targetOffsetX = { it / 6 },
            ),
        ) {
            Box(Modifier.fillMaxSize().background(LocalDshColors.current.bgBase)) {
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
            }
        }
        }
    }
}
