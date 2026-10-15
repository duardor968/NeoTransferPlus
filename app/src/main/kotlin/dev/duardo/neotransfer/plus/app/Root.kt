package dev.duardo.neotransfer.plus.app

import android.Manifest
import android.annotation.SuppressLint
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.duardo.neotransfer.plus.data.enzona.EnzonaLoginWeb
import dev.duardo.neotransfer.plus.data.enzona.EnzonaSessionFailure
import dev.duardo.neotransfer.plus.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.plus.platform.BiometricGate
import dev.duardo.neotransfer.plus.ui.common.AppName
import dev.duardo.neotransfer.plus.ui.common.NeoTransferPlusTheme
import dev.duardo.neotransfer.plus.ui.lock.BrandBackdrop
import dev.duardo.neotransfer.plus.ui.lock.BrandBlock
import dev.duardo.neotransfer.plus.ui.lock.LockScreen
import dev.duardo.neotransfer.plus.ui.login.LoginActions
import dev.duardo.neotransfer.plus.ui.login.LoginScreen
import dev.duardo.neotransfer.plus.ui.model.LockUi
import dev.duardo.neotransfer.plus.ui.model.LoginProblem
import dev.duardo.neotransfer.plus.ui.model.LoginUi
import dev.duardo.neotransfer.plus.ui.payment.PasswordSheet
import kotlinx.coroutines.flow.first

val LocalAuthorizer = staticCompositionLocalOf<Authorizer> { error("Authorizer missing") }
val LocalGate = staticCompositionLocalOf<BiometricGate> { error("BiometricGate missing") }

/** Whole app: theme, first-start permissions, then login, lock or the screens, and the one-line problems. */
@Composable
fun PlusRoot(plus: PlusApp, gate: BiometricGate) {
    val appearance by plus.preferences.appearance.collectAsState()
    val authorizer = remember(plus, gate) { Authorizer(plus, gate) }
    NeoTransferPlusTheme(appearance) {
        CompositionLocalProvider(LocalAuthorizer provides authorizer, LocalGate provides gate) {
            FirstStartPermissions(plus)
            val session by plus.session.collectAsState()
            val locked by plus.locked.collectAsState()
            val snackbar = remember { SnackbarHostState() }
            LaunchedEffect(plus) { plus.messages.collect { snackbar.showSnackbar(it) } }
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
                    locked -> LockRoute(plus, gate)
                    else -> NavHost(plus)
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp)) { data ->
                    Snackbar(
                        Modifier.padding(horizontal = NeoSpace.gutter),
                        shape = MaterialTheme.shapes.medium,
                        containerColor = MaterialTheme.colorScheme.inverseSurface,
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    ) { Text(data.visuals.message, style = MaterialTheme.typography.bodyMedium) }
                }
            }
            authorizer.sheet?.let { PasswordSheet(it, authorizer) }
        }
    }
}

/** Every permission at once on the first start, straight from the system dialogs. */
@Composable
private fun FirstStartPermissions(plus: PlusApp) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(Unit) {
        if (plus.preferences.firstStart()) {
            launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_CONTACTS))
        }
    }
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
    LockScreen(state, ::show)
}

// ---- Login --------------------------------------------------------------------------------------------------

private fun EnzonaSessionFailure.problem(): LoginProblem = when (this) {
    EnzonaSessionFailure.CALLBACK_REJECTED -> LoginProblem.Rejected
    EnzonaSessionFailure.CLIENT_REJECTED -> LoginProblem.ClientRejected
    EnzonaSessionFailure.ACCESS_DENIED -> LoginProblem.Denied
    EnzonaSessionFailure.SERVER_ERROR -> LoginProblem.ServerError
    EnzonaSessionFailure.SIGN_IN_AGAIN -> LoginProblem.Expired
    EnzonaSessionFailure.UNAVAILABLE -> LoginProblem.Offline
    EnzonaSessionFailure.RESTORE_FAILED -> LoginProblem.RestoreFailed
    EnzonaSessionFailure.LOCAL_CLEAR_FAILED -> LoginProblem.ClearFailed
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
