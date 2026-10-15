package dev.duardo.neotransfer.plus.ui.login

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.plus.designsystem.R as Ds
import dev.duardo.neotransfer.plus.designsystem.component.NeoButton
import dev.duardo.neotransfer.plus.designsystem.component.NeoTopBar
import dev.duardo.neotransfer.plus.designsystem.component.SecureWindow
import dev.duardo.neotransfer.plus.designsystem.theme.Neo
import dev.duardo.neotransfer.plus.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.plus.ui.lock.BrandBackdrop
import dev.duardo.neotransfer.plus.ui.lock.BrandBlock
import dev.duardo.neotransfer.plus.ui.model.LoginProblem
import dev.duardo.neotransfer.plus.ui.model.LoginUi

interface LoginActions {
    fun start()
    fun close()
}

val LoginProblem.message: String
    get() = when (this) {
        LoginProblem.Rejected -> "No se pudo completar el acceso. Inténtalo de nuevo."
        LoginProblem.ClientRejected -> "ENZONA no permite el acceso desde esta app."
        LoginProblem.Expired -> "Tu sesión de ENZONA caducó. Entra de nuevo."
        LoginProblem.Offline -> "No hay conexión con ENZONA."
        LoginProblem.PageFailed -> "La página de ENZONA no cargó."
        LoginProblem.RestoreFailed -> "No se pudo leer la sesión guardada. Entra de nuevo."
        LoginProblem.ClearFailed -> "No se pudo borrar la sesión de este teléfono."
    }

/**
 * Access with the ENZONA account. ENZONA's own page opens inside the app ([web] hosts its WebView); the password
 * goes only to ENZONA and the screen is kept out of screenshots while it is shown.
 */
@Composable
fun LoginScreen(state: LoginUi, actions: LoginActions, web: @Composable () -> Unit, modifier: Modifier = Modifier) {
    when (state) {
        is LoginUi.Start -> Welcome(state.problem, actions, modifier)
        is LoginUi.Web -> {
            SecureWindow()
            Scaffold(
                modifier,
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    Column {
                        NeoTopBar("ENZONA", onBack = actions::close, backIcon = Ds.drawable.ic_close, backDescription = "Cerrar")
                        val shown by animateFloatAsState(if (state.loading) 1f else 0f, label = "loading")
                        LinearProgressIndicator(
                            Modifier.fillMaxWidth().height(2.dp).alpha(shown),
                            color = Neo.colors.accent,
                            trackColor = Neo.colors.accentContainer,
                        )
                    }
                },
            ) { padding -> Box(Modifier.fillMaxSize().padding(padding)) { web() } }
        }
        LoginUi.Connecting -> BrandBackdrop(modifier) {
            Spacer(Modifier.weight(0.8f))
            BrandBlock(locked = false)
            Spacer(Modifier.weight(1.2f))
            Row(
                Modifier.navigationBarsPadding().padding(bottom = NeoSpace.huge),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NeoSpace.m),
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Conectando con ENZONA", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Welcome(problem: LoginProblem?, actions: LoginActions, modifier: Modifier) {
    BrandBackdrop(modifier) {
        Spacer(Modifier.weight(0.8f))
        BrandBlock(locked = false)
        Spacer(Modifier.weight(1.2f))
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = NeoSpace.xxl).padding(bottom = NeoSpace.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(NeoSpace.l),
        ) {
            if (problem != null) Row(
                Modifier.fillMaxWidth().background(Neo.colors.warningContainer, MaterialTheme.shapes.medium).padding(NeoSpace.m),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NeoSpace.m),
            ) {
                Box(Modifier.size(32.dp).background(Neo.colors.warning.copy(alpha = 0.16f), CircleShape), contentAlignment = Alignment.Center) {
                    Icon(painterResource(Ds.drawable.ic_error), null, Modifier.size(18.dp), tint = Neo.colors.warning)
                }
                Text(problem.message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Start)
            }
            NeoButton(if (problem == null) "Entrar con ENZONA" else "Entrar de nuevo", actions::start)
        }
    }
}
