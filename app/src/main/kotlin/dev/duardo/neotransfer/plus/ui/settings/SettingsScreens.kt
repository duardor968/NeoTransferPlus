package dev.duardo.neotransfer.plus.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.plus.designsystem.R as Ds
import dev.duardo.neotransfer.plus.designsystem.component.GroupDivider
import dev.duardo.neotransfer.plus.designsystem.component.IconAvatar
import dev.duardo.neotransfer.plus.designsystem.component.NameAvatar
import dev.duardo.neotransfer.plus.designsystem.component.NeoButton
import dev.duardo.neotransfer.plus.designsystem.component.NeoConfirmDialog
import dev.duardo.neotransfer.plus.designsystem.component.NeoGroup
import dev.duardo.neotransfer.plus.designsystem.component.NeoRow
import dev.duardo.neotransfer.plus.designsystem.component.NeoSegmented
import dev.duardo.neotransfer.plus.designsystem.component.NeoTopBar
import dev.duardo.neotransfer.plus.designsystem.component.SecureWindow
import dev.duardo.neotransfer.plus.designsystem.component.SectionHeader
import dev.duardo.neotransfer.plus.designsystem.format.formatPhone
import dev.duardo.neotransfer.plus.designsystem.theme.Neo
import dev.duardo.neotransfer.plus.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.plus.ui.common.AppName
import dev.duardo.neotransfer.plus.ui.common.BottomAction
import dev.duardo.neotransfer.plus.ui.common.BrandMark
import dev.duardo.neotransfer.plus.ui.model.Appearance
import dev.duardo.neotransfer.plus.ui.model.PasswordSettingsUi
import dev.duardo.neotransfer.plus.ui.model.SettingsUi
import dev.duardo.neotransfer.plus.ui.payment.PasswordField

@Composable
private fun Chevron() {
    Icon(painterResource(Ds.drawable.ic_chevron_right), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SettingsScaffold(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, bottom: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Scaffold(
        modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { NeoTopBar(title, onBack = onBack) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = NeoSpace.gutter).padding(bottom = NeoSpace.xxl),
            ) { content() }
            bottom?.invoke()
        }
    }
}

// ---- Settings home ------------------------------------------------------------------------------------------

interface SettingsActions {
    fun back()
    fun signOut()
    fun confirmSignOut()
    fun cancelSignOut()
    fun password()
    fun appearance(value: Appearance)
    fun notifications()
    fun about()
}

@Composable
fun SettingsScreen(state: SettingsUi, actions: SettingsActions, modifier: Modifier = Modifier) {
    SettingsScaffold("Ajustes", actions::back, modifier) {
        SectionHeader("Cuenta ENZONA")
        NeoGroup {
            state.account?.let { account ->
                NeoRow(
                    account.name,
                    supporting = listOfNotNull(account.username, account.phone?.let(::formatPhone)).joinToString(" · ").ifEmpty { null },
                    leading = { NameAvatar(account.name) },
                )
                GroupDivider()
            }
            NeoRow(
                "Contraseña de pago",
                supporting = if (state.passwordSaved) "Guardada con tu huella" else "Sin guardar",
                leading = { IconAvatar(Ds.drawable.ic_key) },
                trailing = { Chevron() },
                onClick = actions::password,
            )
            GroupDivider()
            NeoRow(
                "Cerrar sesión",
                leading = { IconAvatar(Ds.drawable.ic_logout, tint = Neo.colors.danger) },
                titleColor = Neo.colors.danger,
                trailing = if (state.signingOut) ({ CircularProgressIndicator(Modifier.padding(end = NeoSpace.s), strokeWidth = 2.dp) }) else null,
                onClick = if (state.signingOut) null else actions::signOut,
            )
        }
        SectionHeader("Apariencia", Modifier.padding(top = NeoSpace.l))
        NeoSegmented(Appearance.entries, state.appearance, { it.label }, actions::appearance)
        SectionHeader("General", Modifier.padding(top = NeoSpace.l))
        NeoGroup {
            NeoRow(
                "Notificaciones",
                supporting = if (state.notifications) "Activadas" else "Desactivadas",
                leading = { IconAvatar(Ds.drawable.ic_notifications) },
                trailing = { Chevron() },
                onClick = actions::notifications,
            )
            GroupDivider()
            NeoRow(
                "Acerca de",
                supporting = "Versión ${state.version}",
                leading = { IconAvatar(Ds.drawable.ic_info) },
                trailing = { Chevron() },
                onClick = actions::about,
            )
        }
    }
    if (state.confirmingSignOut) NeoConfirmDialog(
        title = "¿Cerrar sesión?",
        text = "Se borran de este teléfono la sesión, las tarjetas, los saldos y la actividad de ENZONA. Tus contactos se conservan.",
        confirm = "Cerrar sesión",
        dismiss = "Cancelar",
        onConfirm = actions::confirmSignOut,
        onDismiss = actions::cancelSignOut,
        destructive = true,
    )
}

// ---- Payment password ---------------------------------------------------------------------------------------

interface PasswordSettingsActions {
    fun back()
    fun value(value: String)
    fun save()
    fun remove()
    fun confirmRemove()
    fun cancelRemove()
}

/** Saves or replaces the ENZONA payment password behind the fingerprint. Secret: no screenshots. */
@Composable
fun PasswordSettingsScreen(state: PasswordSettingsUi, actions: PasswordSettingsActions, modifier: Modifier = Modifier) {
    SecureWindow()
    SettingsScaffold(
        "Contraseña de pago", actions::back, modifier,
        bottom = {
            BottomAction {
                NeoButton(if (state.saved) "Reemplazar con tu huella" else "Guardar con tu huella", actions::save, icon = Ds.drawable.ic_fingerprint, enabled = state.value.isNotEmpty())
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(NeoSpace.m)) {
            PasswordField(state.value, actions::value, if (state.saved) "Nueva contraseña" else "Contraseña", state.error)
            if (state.saved) NeoGroup(Modifier.padding(top = NeoSpace.l)) {
                NeoRow(
                    "Borrar la contraseña guardada",
                    leading = { IconAvatar(Ds.drawable.ic_delete, tint = Neo.colors.danger) },
                    titleColor = Neo.colors.danger,
                    onClick = actions::remove,
                )
            }
        }
    }
    if (state.confirmingRemoval) NeoConfirmDialog(
        title = "¿Borrar la contraseña?",
        text = "El próximo pago la pedirá de nuevo.",
        confirm = "Borrar",
        dismiss = "Cancelar",
        onConfirm = actions::confirmRemove,
        onDismiss = actions::cancelRemove,
        destructive = true,
    )
}

// ---- About --------------------------------------------------------------------------------------------------

interface AboutActions {
    fun back()
    fun source()
    fun licenses()
}

@Composable
fun AboutScreen(version: String, actions: AboutActions, modifier: Modifier = Modifier) {
    SettingsScaffold("Acerca de", actions::back, modifier) {
        Column(Modifier.fillMaxWidth().padding(vertical = NeoSpace.xxl), horizontalAlignment = Alignment.CenterHorizontally) {
            BrandMark(96.dp)
            Text(AppName, Modifier.padding(top = NeoSpace.l), style = MaterialTheme.typography.headlineSmall)
            Text("Versión $version", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        NeoGroup {
            NeoRow("Código fuente", supporting = "github.com/duardor968/NeoTransferPlus", leading = { IconAvatar(Ds.drawable.ic_code) }, trailing = { Chevron() }, onClick = actions::source)
            GroupDivider()
            NeoRow("Licencias", supporting = "Inter, Arimo, Material Symbols", leading = { IconAvatar(Ds.drawable.ic_description) }, trailing = { Chevron() }, onClick = actions::licenses)
        }
    }
}

/** The licenses shipped with the app, as their files say them. */
@Composable
fun LicensesScreen(licenses: List<Pair<String, String>>, onBack: () -> Unit, modifier: Modifier = Modifier) {
    SettingsScaffold("Licencias", onBack, modifier) {
        licenses.forEach { (name, text) ->
            SectionHeader(name, Modifier.padding(top = NeoSpace.s))
            NeoGroup {
                Text(text, Modifier.padding(NeoSpace.l), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
