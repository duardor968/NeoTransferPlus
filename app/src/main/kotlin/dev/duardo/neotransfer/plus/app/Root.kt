package dev.duardo.neotransfer.plus.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.os.CancellationSignal
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.duardo.neotransfer.plus.data.enzona.EnzonaLoginWeb
import dev.duardo.neotransfer.plus.data.enzona.EnzonaSessionFailure
import dev.duardo.neotransfer.plus.designsystem.component.KeypadKey
import dev.duardo.neotransfer.plus.designsystem.component.NeoTextButton
import dev.duardo.neotransfer.plus.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.plus.platform.BiometricGate
import dev.duardo.neotransfer.plus.platform.PinLock
import dev.duardo.neotransfer.plus.platform.autostartSettings
import dev.duardo.neotransfer.plus.platform.batteryExemptionRequest
import dev.duardo.neotransfer.plus.platform.ignoresBatteryOptimizations
import dev.duardo.neotransfer.plus.platform.openAutostartSettings
import dev.duardo.neotransfer.plus.ui.common.AppName
import dev.duardo.neotransfer.plus.ui.common.LocalApproval
import dev.duardo.neotransfer.plus.ui.common.NeoTransferPlusTheme
import dev.duardo.neotransfer.plus.ui.lock.BrandBackdrop
import dev.duardo.neotransfer.plus.ui.lock.BrandBlock
import dev.duardo.neotransfer.plus.ui.lock.CodeLockActions
import dev.duardo.neotransfer.plus.ui.lock.CodeLockScreen
import dev.duardo.neotransfer.plus.ui.lock.CodeSetupActions
import dev.duardo.neotransfer.plus.ui.lock.CodeSetupScreen
import dev.duardo.neotransfer.plus.ui.lock.CodeSheet
import dev.duardo.neotransfer.plus.ui.lock.LockScreen
import dev.duardo.neotransfer.plus.ui.login.LoginActions
import dev.duardo.neotransfer.plus.ui.login.LoginScreen
import dev.duardo.neotransfer.plus.ui.model.CodeLockUi
import dev.duardo.neotransfer.plus.ui.model.CodeSetupUi
import dev.duardo.neotransfer.plus.ui.model.ForgotUi
import dev.duardo.neotransfer.plus.ui.model.LockUi
import dev.duardo.neotransfer.plus.ui.model.LoginProblem
import dev.duardo.neotransfer.plus.ui.model.LoginUi
import dev.duardo.neotransfer.plus.ui.model.PinProtection
import dev.duardo.neotransfer.plus.ui.payment.PasswordSheet
import dev.duardo.neotransfer.plus.ui.settings.AutostartSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val LocalAuthorizer = staticCompositionLocalOf<Authorizer> { error("Authorizer missing") }

/** Whole app: theme, first-start permissions, then login, lock or the screens, and the one-line problems. */
@Composable
fun PlusRoot(plus: PlusApp, gate: BiometricGate) {
    val appearance by plus.preferences.appearance.collectAsState()
    val authorizer = remember(plus, gate) { Authorizer(plus.vault, plus.code, gate, plus.scope) }
    NeoTransferPlusTheme(appearance) {
        val pinLock by plus.vault.lock.collectAsState()
        val unverified by plus.vault.unverified.collectAsState()
        val protectsPin by plus.code.protectsPin.collectAsState()
        // The phone's own locks may change while the app is away.
        var resumes by remember { mutableIntStateOf(0) }
        LifecycleResumeEffect(Unit) {
            resumes++
            onPauseOrDispose { }
        }
        val approval = remember(pinLock, unverified, protectsPin, resumes) { authorizer.approval() }
        CompositionLocalProvider(LocalAuthorizer provides authorizer, LocalApproval provides approval) {
            FirstStartPermissions(plus)
            val session by plus.session.collectAsState()
            val locked by plus.locked.collectAsState()
            val codeActive by plus.code.active.collectAsState()
            val snackbar = remember { SnackbarHostState() }
            LaunchedEffect(plus) {
                plus.messages.collect { message ->
                    val result = snackbar.showSnackbar(message.text, message.action, duration = SnackbarDuration.Short)
                    if (result == SnackbarResult.ActionPerformed) message.onAction()
                }
            }
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                val needsLogin = !session.signedIn || session.failure == EnzonaSessionFailure.SIGN_IN_AGAIN ||
                    session.failure == EnzonaSessionFailure.RESTORE_FAILED
                when {
                    !session.restored -> BrandBackdrop {
                        Spacer(Modifier.weight(0.8f))
                        BrandBlock(locked = false)
                        Spacer(Modifier.weight(1.2f))
                    }
                    needsLogin -> LoginRoute(plus)
                    locked && codeActive -> CodeLockRoute(plus, gate)
                    locked -> LockRoute(plus, gate)
                    else -> NavHost(plus)
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp)) { data ->
                    Snackbar(
                        Modifier.padding(horizontal = NeoSpace.gutter),
                        action = data.visuals.actionLabel?.let { label ->
                            { NeoTextButton(label, data::performAction, color = MaterialTheme.colorScheme.inversePrimary) }
                        },
                        shape = MaterialTheme.shapes.medium,
                        containerColor = MaterialTheme.colorScheme.inverseSurface,
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    ) { Text(data.visuals.message, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            authorizer.sheet?.let { PasswordSheet(it, authorizer) }
            authorizer.codeSheet?.let { CodeSheet(it, authorizer.codeActions) }
        }
    }
}

/**
 * Every permission at once on the first start, straight from the system dialogs. Then, once, what lets the credit
 * check run with the app closed: the exemption from battery optimization (system dialog) and, on phones that have
 * one, their own autostart list.
 */
@Composable
private fun FirstStartPermissions(plus: PlusApp) {
    val context = LocalContext.current
    var offerAutostart by rememberSaveable { mutableStateOf(false) }
    val battery = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        offerAutostart = context.ignoresBatteryOptimizations() && context.autostartSettings() != null
    }
    fun askBackground() {
        if (!plus.preferences.firstBackgroundAsk()) return
        when {
            // A phone without the system dialog goes straight to its autostart list, if it has one.
            !context.ignoresBatteryOptimizations() && runCatching { battery.launch(context.batteryExemptionRequest()) }.isSuccess -> Unit
            context.autostartSettings() != null -> offerAutostart = true
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        plus.readAddressBook()
        askBackground()
    }
    LaunchedEffect(Unit) {
        if (plus.preferences.firstStart()) {
            launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_CONTACTS))
        } else {
            askBackground()
        }
    }
    if (offerAutostart) AutostartSheet(
        onOpen = {
            offerAutostart = false
            if (!context.openAutostartSettings()) plus.say("No se pudo abrir el inicio automático")
        },
        onDismiss = { offerAutostart = false },
    )
}

// ---- Lock ---------------------------------------------------------------------------------------------------

/** Prompts by itself when the lock engages or the app returns; a cancel leaves one button, never a loop. */
@Composable
private fun LockRoute(plus: PlusApp, gate: BiometricGate) {
    var state by remember { mutableStateOf<LockUi>(LockUi.Prompting) }
    var prompt by remember { mutableStateOf<CancellationSignal?>(null) }
    val epoch by plus.lockEpoch.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun show() {
        if (prompt != null) return
        state = LockUi.Prompting
        prompt = gate.unlock(
            "Desbloquear $AppName",
            onSuccess = { prompt = null; plus.unlock() },
            onCancelled = { prompt = null; state = LockUi.Cancelled },
        )
    }
    LaunchedEffect(epoch) {
        lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        show()
    }
    DisposableEffect(Unit) { onDispose { prompt?.cancel() } }
    // The unlock prompt always takes the screen lock too: the fingerprint icon only where a usable fingerprint key is.
    val approval = if (plus.vault.lockFor(codeProtects = false) == PinLock.Fingerprint) PinProtection.Fingerprint else PinProtection.ScreenLock
    CompositionLocalProvider(LocalApproval provides approval) { LockScreen(state, ::show) }
}

/**
 * The lock while the access code is set: the code or, when the lock engages or the app returns, the fingerprint by
 * itself if the code's fingerprint key still trusts the enrolled fingers. Never the phone's own PIN or pattern.
 */
@Composable
private fun CodeLockRoute(plus: PlusApp, gate: BiometricGate) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val epoch by plus.lockEpoch.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var fingerprint by remember { mutableStateOf(false) }
    var forgot by remember { mutableStateOf<ForgotUi?>(null) }
    var prompt by remember { mutableStateOf<CancellationSignal?>(null) }
    var newCode by remember { mutableStateOf<NewCode?>(null) }
    val entry = remember { CodeEntry(plus.code, scope, Dispatchers.Default) { plus.unlock() } }
    fun withFingerprint(onOpened: () -> Unit) {
        if (prompt != null) return
        val cipher = plus.code.fingerprintCipher() ?: return run { fingerprint = false }
        prompt = gate.authenticate(
            cipher, "Desbloquear $AppName", null, screenLock = false,
            onSuccess = { authorized ->
                prompt = null
                if (plus.code.fingerprintOpens(authorized)) onOpened() else fingerprint = false
            },
            onCancelled = { prompt = null },
            onError = { message -> prompt = null; plus.say(message) },
        )
    }
    LaunchedEffect(epoch) {
        lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        fingerprint = plus.code.fingerprintCipher() != null
        // Not during a wait after wrong codes: the fingerprint is off then, as the keypad.
        if (fingerprint && newCode == null && forgot == null && plus.code.remaining() == 0L) withFingerprint { plus.unlock() }
    }
    DisposableEffect(Unit) { onDispose { prompt?.cancel(); entry.close(); newCode?.close() } }
    newCode?.let { code ->
        BackHandler { code.close(); newCode = null }
        CodeSetupScreen(CodeSetupUi(code.step, code.ui), object : CodeSetupActions {
            override fun key(key: KeypadKey) = code.key(key) { typed ->
                scope.launch {
                    // What the forgotten code protected cannot be opened any more.
                    val created = runCatching {
                        withContext(Dispatchers.Default) {
                            plus.code.create(typed) { if (plus.vault.lock.value == PinLock.Code) plus.vault.clear() }
                        }
                    }.isSuccess
                    typed.fill('\u0000')
                    newCode = null
                    if (created) plus.unlock() else plus.say("No se pudo guardar el código")
                }
            }
            override fun back() { code.close(); newCode = null }
        })
        return
    }
    val actions = object : CodeLockActions {
        override fun key(key: KeypadKey) = entry.key(key)
        override fun fingerprint() {
            if (plus.code.remaining() == 0L) withFingerprint { plus.unlock() }
        }
        override fun forgot() {
            forgot = ForgotUi(erasesPin = fingerprint && plus.vault.lock.value == PinLock.Code, confirming = !fingerprint)
        }
        override fun closeForgot() { forgot = null }
        override fun forgotFingerprint() {
            forgot = null
            withFingerprint { newCode = NewCode() }
        }
        override fun erase() { forgot = forgot?.copy(confirming = true) }
        override fun confirmErase() {
            forgot = null
            if (!context.getSystemService(ActivityManager::class.java).clearApplicationUserData()) plus.say("No se pudieron borrar los datos")
        }
    }
    CodeLockScreen(CodeLockUi(entry.ui, fingerprint, forgot), actions)
}

// ---- Login --------------------------------------------------------------------------------------------------

private fun EnzonaSessionFailure.problem(): LoginProblem = when (this) {
    EnzonaSessionFailure.CALLBACK_REJECTED -> LoginProblem.Rejected
    EnzonaSessionFailure.CLIENT_REJECTED -> LoginProblem.ClientRejected
    EnzonaSessionFailure.ACCESS_DENIED -> LoginProblem.Denied
    EnzonaSessionFailure.SERVER_ERROR -> LoginProblem.ServerError
    EnzonaSessionFailure.SIGN_IN_AGAIN -> LoginProblem.Expired
    EnzonaSessionFailure.TIMEOUT -> LoginProblem.Timeout
    EnzonaSessionFailure.UNAVAILABLE -> LoginProblem.Offline
    EnzonaSessionFailure.RESTORE_FAILED -> LoginProblem.RestoreFailed
    EnzonaSessionFailure.LOCAL_CLEAR_FAILED -> LoginProblem.ClearFailed
    EnzonaSessionFailure.LOCAL_SAVE_FAILED -> LoginProblem.SaveFailed
}

/** ENZONA's own sign-in page inside the app; the callback never leaves the phone (see [EnzonaLoginWeb]). */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LoginRoute(plus: PlusApp) {
    val session by plus.session.collectAsState()
    var pageLoading by remember { mutableStateOf(true) }
    var pageFailed by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    val url = session.authorizationUrl
    val state = when {
        url != null && session.busy -> LoginUi.Connecting
        url != null -> LoginUi.Web(pageLoading)
        else -> LoginUi.Start(if (pageFailed) LoginProblem.PageFailed else session.failure?.problem())
    }
    val actions = remember(plus) {
        object : LoginActions {
            override fun start() {
                pageFailed = false
                pageLoading = true
                plus.repository.beginLogin()
            }
            override fun close() = plus.repository.cancelLogin()
        }
    }
    BackHandler(url != null) {
        val view = web
        if (view != null && view.canGoBack() && !session.busy) view.goBack() else plus.repository.cancelLogin()
    }
    LoginScreen(state, actions, web = {
        if (url != null) key(url) { AndroidView(
            factory = { context ->
                WebView(context).also { view ->
                    web = view
                    EnzonaLoginWeb.attach(view, url, object : EnzonaLoginWeb.Listener {
                        override fun onCallback(url: String) {
                            // A callback this attempt did not expect ends it: the person starts again.
                            if (!plus.repository.acceptLoginCallback(url, isMainFrame = true)) plus.repository.cancelLogin()
                        }
                        override fun onLoading(loading: Boolean) {
                            pageLoading = loading
                        }
                        override fun onBlocked() {
                            pageFailed = true
                            plus.repository.cancelLogin()
                        }
                    })
                }
            },
            onRelease = { view ->
                web = null
                EnzonaLoginWeb.release(view)
            },
            modifier = Modifier.fillMaxSize(),
        ) }
    })
}
