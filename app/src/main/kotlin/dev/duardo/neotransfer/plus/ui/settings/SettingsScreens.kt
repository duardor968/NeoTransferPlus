package dev.duardo.neotransfer.plus.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import dev.duardo.neotransfer.plus.designsystem.component.NeoConfirmSheet
import dev.duardo.neotransfer.plus.designsystem.component.NeoGroup
import dev.duardo.neotransfer.plus.designsystem.component.NeoIconButton
import dev.duardo.neotransfer.plus.designsystem.component.NeoRow
import dev.duardo.neotransfer.plus.designsystem.component.NeoSegmented
import dev.duardo.neotransfer.plus.designsystem.component.NeoTopBar
import dev.duardo.neotransfer.plus.designsystem.component.SecureWindow
import dev.duardo.neotransfer.plus.designsystem.component.SectionHeader
import dev.duardo.neotransfer.plus.designsystem.component.flushEnd
import dev.duardo.neotransfer.plus.designsystem.format.NoBreak
import dev.duardo.neotransfer.plus.designsystem.format.formatPhone
import dev.duardo.neotransfer.plus.designsystem.theme.Neo
import dev.duardo.neotransfer.plus.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.plus.ui.common.AppName
import dev.duardo.neotransfer.plus.ui.common.BottomAction
import dev.duardo.neotransfer.plus.ui.common.BrandMark
import dev.duardo.neotransfer.plus.ui.common.CardThumb
import dev.duardo.neotransfer.plus.ui.common.numberLine
import dev.duardo.neotransfer.plus.ui.model.AccountCardUi
import dev.duardo.neotransfer.plus.ui.model.Appearance
import dev.duardo.neotransfer.plus.ui.model.PasswordSettingsUi
import dev.duardo.neotransfer.plus.ui.model.SettingsUi
import dev.duardo.neotransfer.plus.ui.model.issuerOf
import dev.duardo.neotransfer.plus.ui.payment.PasswordField
import dev.duardo.neotransfer.plus.ui.payment.isPaymentPassword

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
    /** Battery exemption first; then, with the phone's own autostart list, the offer to open it. */
    fun background()
    fun openAutostart()
    fun closeAutostart()
    fun about()
    fun addCard()
    fun cardMenu(cardId: String)
    fun closeCardMenu()
    fun makeDefault(cardId: String)
    /** Asks first ([SettingsUi.removing]). */
    fun removeCard(cardId: String)
    fun confirmRemoveCard()
    fun cancelRemoveCard()
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
                "PIN de pago",
                supporting = when {
                    !state.passwordSaved -> "Sin guardar"
                    state.passwordUnverified -> "Por comprobar"
                    else -> "Guardado con tu huella"
                },
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
        SectionHeader("Tarjetas", Modifier.padding(top = NeoSpace.l))
        NeoGroup {
            state.cards.forEach { account ->
                AccountCardRow(account, state.cardMenu == account.card.id, actions)
                GroupDivider()
            }
            NeoRow("Añadir tarjeta", leading = { IconAvatar(Ds.drawable.ic_add_card) }, onClick = actions::addCard)
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
            val actionable = !state.batteryExempt || state.autostart
            NeoRow(
                "Avisos con la app cerrada",
                supporting = if (state.batteryExempt) "Sin límite de batería" else "Limitados por el ahorro de batería",
                leading = { IconAvatar(Ds.drawable.ic_schedule) },
                trailing = if (actionable) ({ Chevron() }) else null,
                onClick = if (actionable) actions::background else null,
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
    if (state.offeringAutostart) AutostartSheet(actions::openAutostart, actions::closeAutostart)
    state.removing?.let { card ->
        NeoConfirmSheet(
            title = "¿Quitar ${listOfNotNull(card.bank, card.last4?.let { "••••$NoBreak$it" }).joinToString(NoBreak.toString())}?",
            text = "Deja de estar en tu cuenta de ENZONA hasta que la añadas otra vez. Tu actividad se conserva.",
            confirm = "Quitar",
            dismiss = "Cancelar",
            onConfirm = actions::confirmRemoveCard,
            onDismiss = actions::cancelRemoveCard,
            destructive = true,
        )
    }
    if (state.confirmingSignOut) NeoConfirmSheet(
        title = "¿Cerrar sesión?",
        text = "Se borran de este teléfono la sesión, las tarjetas, los saldos y la actividad de ENZONA. Tus contactos se conservan.",
        confirm = "Cerrar sesión",
        dismiss = "Cancelar",
        onConfirm = actions::confirmSignOut,
        onDismiss = actions::cancelSignOut,
        destructive = true,
    )
}

/** One card of the account; its menu makes it the default one or removes it. */
@Composable
private fun AccountCardRow(account: AccountCardUi, menuOpen: Boolean, actions: SettingsActions) {
    val card = account.card
    NeoRow(
        card.numberLine,
        fitTitle = true,
        supporting = listOfNotNull(issuerOf(card.bank, card.currency), "Predeterminada".takeIf { account.primary }).joinToString(" · "),
        leading = { CardThumb(card) },
        trailing = {
            // The spinner takes the ⋮'s own slot, so the row keeps its height; both line up with the › of the other rows.
            if (account.busy) Box(Modifier.flushEnd().size(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            else Box(Modifier.flushEnd()) {
                NeoIconButton(Ds.drawable.ic_more_vert, "Opciones de la tarjeta", { actions.cardMenu(card.id) })
                DropdownMenu(menuOpen, actions::closeCardMenu, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    if (!account.primary) DropdownMenuItem(
                        { Text("Predeterminada") },
                        { actions.makeDefault(card.id) },
                        leadingIcon = { Icon(painterResource(Ds.drawable.ic_check_circle), null) },
                    )
                    DropdownMenuItem(
                        { Text("Quitar", color = Neo.colors.danger) },
                        { actions.removeCard(card.id) },
                        leadingIcon = { Icon(painterResource(Ds.drawable.ic_delete), null, tint = Neo.colors.danger) },
                    )
                }
            }
        },
    )
}

/** The phone's own autostart list stops the app when it is closed: one step, said before opening it. */
@Composable
fun AutostartSheet(onOpen: () -> Unit, onDismiss: () -> Unit) {
    NeoConfirmSheet(
        title = "Inicio automático",
        text = "Comprueba que $AppName esté activado en la lista para recibir avisos con la app cerrada.",
        confirm = "Abrir lista",
        dismiss = "Ahora no",
        onConfirm = onOpen,
        onDismiss = onDismiss,
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
        "PIN de pago", actions::back, modifier,
        bottom = {
            BottomAction {
                NeoButton(if (state.saved) "Reemplazar con tu huella" else "Guardar con tu huella", actions::save, icon = Ds.drawable.ic_fingerprint, enabled = isPaymentPassword(state.value))
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(NeoSpace.m)) {
            PasswordField(state.value, actions::value, if (state.saved) "PIN nuevo" else "PIN", state.error)
            Text("Si ENZONA lo bloquea, restablécelo en su app, en «Resetear contraseña de pago».", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.saved) NeoGroup(Modifier.padding(top = NeoSpace.l)) {
                NeoRow(
                    "Borrar el PIN guardado",
                    leading = { IconAvatar(Ds.drawable.ic_delete, tint = Neo.colors.danger) },
                    titleColor = Neo.colors.danger,
                    onClick = actions::remove,
                )
            }
        }
    }
    if (state.confirmingRemoval) NeoConfirmSheet(
        title = "¿Borrar el PIN?",
        text = "El próximo pago lo pedirá de nuevo.",
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
