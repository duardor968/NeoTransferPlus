package dev.duardo.neotransfer.plus.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.plus.designsystem.R as Ds
import dev.duardo.neotransfer.plus.designsystem.component.KeypadExtra
import dev.duardo.neotransfer.plus.designsystem.component.KeypadKey
import dev.duardo.neotransfer.plus.designsystem.component.NeoBottomSheet
import dev.duardo.neotransfer.plus.designsystem.component.NeoButton
import dev.duardo.neotransfer.plus.designsystem.component.NeoButtonStyle
import dev.duardo.neotransfer.plus.designsystem.component.NeoKeypad
import dev.duardo.neotransfer.plus.designsystem.component.NeoTextButton
import dev.duardo.neotransfer.plus.designsystem.component.NeoTopBar
import dev.duardo.neotransfer.plus.designsystem.component.PinDots
import dev.duardo.neotransfer.plus.designsystem.component.SecureWindow
import dev.duardo.neotransfer.plus.designsystem.format.NoBreak
import dev.duardo.neotransfer.plus.designsystem.theme.Neo
import dev.duardo.neotransfer.plus.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.plus.ui.common.AppName
import dev.duardo.neotransfer.plus.ui.common.BrandMark
import dev.duardo.neotransfer.plus.ui.model.CODE_LENGTH
import dev.duardo.neotransfer.plus.ui.model.CodeEntryUi
import dev.duardo.neotransfer.plus.ui.model.CodeLockUi
import dev.duardo.neotransfer.plus.ui.model.CodeSetupUi
import dev.duardo.neotransfer.plus.ui.model.CodeSheetUi
import dev.duardo.neotransfer.plus.ui.model.CodeStep
import dev.duardo.neotransfer.plus.ui.model.ForgotUi

/** The six dots of the access code and, under them, what the last attempt means. */
@Composable
private fun CodeDots(entry: CodeEntryUi, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        PinDots(CODE_LENGTH, entry.filled, error = entry.error)
        // Room for two lines, so the keypad does not jump when a message comes and goes.
        Box(Modifier.heightIn(min = 64.dp).padding(horizontal = NeoSpace.gutter).padding(top = NeoSpace.l), contentAlignment = Alignment.TopCenter) {
            entry.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Neo.colors.danger, textAlign = TextAlign.Center) }
        }
    }
}

/** The keypad of every code: edge to edge, keeping the screen's gutter itself; off while a wait runs. */
@Composable
private fun CodeKeypad(onKey: (KeypadKey) -> Unit, entry: CodeEntryUi, modifier: Modifier = Modifier, extra: KeypadExtra? = null) =
    NeoKeypad(onKey, modifier.padding(horizontal = NeoSpace.gutter), decimal = false, extra = extra, enabled = !entry.waiting)

// ---- Lock -----------------------------------------------------------------------------------------------------

interface CodeLockActions {
    fun key(key: KeypadKey)
    fun fingerprint()
    fun forgot()
    fun closeForgot()
    /** From «¿Olvidaste el código?»: the fingerprint, then a new code. */
    fun forgotFingerprint()
    /** Moves the sheet to the confirmation of erasing the app's data. */
    fun erase()
    fun confirmErase()
}

/** The lock while the access code is set: the code, or a fingerprint enrolled before it ([CodeLockUi.fingerprint]). */
@Composable
fun CodeLockScreen(state: CodeLockUi, actions: CodeLockActions, modifier: Modifier = Modifier) {
    BrandBackdrop(modifier) {
        Spacer(Modifier.weight(1f))
        BrandMark(64.dp)
        Text("Escribe el código", Modifier.padding(top = NeoSpace.xl), style = MaterialTheme.typography.titleLarge)
        CodeDots(state.entry, Modifier.padding(top = NeoSpace.xxl))
        Spacer(Modifier.weight(1f))
        CodeKeypad(actions::key, state.entry, extra = KeypadExtra(Ds.drawable.ic_fingerprint, "Usar la huella", actions::fingerprint).takeIf { state.fingerprint })
        NeoTextButton("¿Olvidaste el código?", actions::forgot, Modifier.padding(vertical = NeoSpace.s), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    state.forgot?.let { ForgotSheet(it, actions) }
}

/**
 * One sheet in two steps, as the app's confirmations: first the fingerprint's way back (when its key still trusts the
 * fingers) as the second action and erasing the app's data as the destructive one; then, in the same sheet, what
 * erasing means before «Borrar». Without that fingerprint it opens on the second step.
 */
@Composable
private fun ForgotSheet(state: ForgotUi, actions: CodeLockActions) {
    NeoBottomSheet(onDismissRequest = actions::closeForgot) {
        Column(Modifier.padding(horizontal = NeoSpace.gutter)) {
            if (state.confirming) {
                Text("¿Borrar los datos de la app?", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Sin el código solo puedes empezar de nuevo: se borran todos los datos de $AppName", Modifier.padding(top = NeoSpace.s),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                NeoButton("Borrar", actions::confirmErase, Modifier.padding(top = NeoSpace.xxl), style = NeoButtonStyle.Danger)
            } else {
                Text("¿Olvidaste el código?", style = MaterialTheme.typography.headlineSmall)
                if (state.erasesPin) Text(
                    "Si entras con la huella se borrará el PIN de pago guardado", Modifier.padding(top = NeoSpace.s),
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.padding(top = NeoSpace.xxl), verticalArrangement = Arrangement.spacedBy(NeoSpace.s)) {
                    NeoButton("Entrar con la huella", actions::forgotFingerprint, style = NeoButtonStyle.Tonal, icon = Ds.drawable.ic_fingerprint)
                    NeoButton("Borrar los datos de la app", actions::erase, style = NeoButtonStyle.Danger)
                }
            }
            NeoTextButton(
                "Cancelar", actions::closeForgot, Modifier.fillMaxWidth().padding(top = NeoSpace.xs).heightIn(min = 48.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---- Setting a code -------------------------------------------------------------------------------------------

interface CodeSetupActions {
    fun key(key: KeypadKey)
    fun back()
}

/** A code typed on a screen of its own: the current one first when it changes, then the new one twice. */
@Composable
fun CodeSetupScreen(state: CodeSetupUi, actions: CodeSetupActions, modifier: Modifier = Modifier) {
    SecureWindow()
    Scaffold(modifier, containerColor = MaterialTheme.colorScheme.background, topBar = { NeoTopBar("Código de acceso", onBack = actions::back) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            Text(
                when (state.step) {
                    CodeStep.Current -> "Escribe el código"
                    CodeStep.Create -> "Crea un código de$NoBreak$CODE_LENGTH${NoBreak}dígitos"
                    CodeStep.Repeat -> "Repite el código"
                },
                Modifier.padding(horizontal = NeoSpace.gutter),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            CodeDots(state.entry, Modifier.padding(top = NeoSpace.xxl))
            Spacer(Modifier.weight(1f))
            CodeKeypad(actions::key, state.entry, Modifier.padding(bottom = NeoSpace.l))
        }
    }
}

// ---- Asked in a sheet -----------------------------------------------------------------------------------------

interface CodeSheetActions {
    fun key(key: KeypadKey)
    fun dismiss()
}

/** The access code for one payment or one change of what it protects. */
@Composable
fun CodeSheet(state: CodeSheetUi, actions: CodeSheetActions) {
    SecureWindow()
    NeoBottomSheet(onDismissRequest = actions::dismiss, title = state.title) {
        state.subtitle?.let {
            Text(it, Modifier.padding(horizontal = NeoSpace.gutter), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.fillMaxWidth().padding(top = NeoSpace.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(NeoSpace.l)) {
            Text("Escribe el código", style = MaterialTheme.typography.titleMedium)
            CodeDots(state.entry)
            CodeKeypad(actions::key, state.entry)
        }
    }
}
