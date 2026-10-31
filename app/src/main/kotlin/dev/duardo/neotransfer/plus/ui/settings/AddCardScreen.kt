package dev.duardo.neotransfer.plus.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.plus.designsystem.R as Ds
import dev.duardo.neotransfer.plus.designsystem.component.NeoButton
import dev.duardo.neotransfer.plus.designsystem.component.NeoIconButton
import dev.duardo.neotransfer.plus.designsystem.component.NeoTextField
import dev.duardo.neotransfer.plus.designsystem.component.NeoTopBar
import dev.duardo.neotransfer.plus.designsystem.component.SecureWindow
import dev.duardo.neotransfer.plus.designsystem.component.SeparatedText
import dev.duardo.neotransfer.plus.designsystem.component.StatusMark
import dev.duardo.neotransfer.plus.designsystem.component.StatusTone
import dev.duardo.neotransfer.plus.designsystem.format.unbroken
import dev.duardo.neotransfer.plus.designsystem.theme.Neo
import dev.duardo.neotransfer.plus.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.plus.ui.common.BankCard
import dev.duardo.neotransfer.plus.ui.common.BottomAction
import dev.duardo.neotransfer.plus.ui.common.CardNumberTransformation
import dev.duardo.neotransfer.plus.ui.common.ConfirmedMark
import dev.duardo.neotransfer.plus.ui.common.ExpiryTransformation
import dev.duardo.neotransfer.plus.ui.common.ForeignCardFace
import dev.duardo.neotransfer.plus.ui.common.numberLine
import dev.duardo.neotransfer.plus.ui.model.AddCardUi
import dev.duardo.neotransfer.plus.ui.model.CardArt
import dev.duardo.neotransfer.plus.ui.model.CardUi
import dev.duardo.neotransfer.plus.ui.model.ForeignCardUi
import dev.duardo.neotransfer.plus.ui.model.NotAddedKind
import dev.duardo.neotransfer.plus.ui.model.issuerOf

interface AddCardActions {
    /** ✕ leaves; from the cells, ← goes back to the number. */
    fun back()
    fun number(value: String)
    fun expiry(value: String)
    fun scan()
    /** Asks ENZONA for the cells of this attempt. */
    fun next()
    fun cell(index: Int, value: String)
    fun send()
    /** After a refusal: the number and expiry again, then new cells. */
    fun review()
    /** Back to Ajustes. */
    fun done()
}

/**
 * Adds a card to the ENZONA account: its number and expiry; then the cells ENZONA names for this attempt, from the
 * coordinates card and the card's PIN, typed for this send only and hidden from screenshots; then the send, on a screen
 * of its own until ENZONA answers.
 */
@Composable
fun AddCardScreen(state: AddCardUi, actions: AddCardActions, modifier: Modifier = Modifier) {
    when (state) {
        is AddCardUi.Form -> CardStep(state, actions, modifier)
        is AddCardUi.Cells -> CellsStep(state, actions, modifier)
        // One call site, so the mark turns into the answer instead of being replaced.
        is AddCardUi.Sent -> SendStatus(state, actions, modifier)
    }
}

@Composable
private fun CardStep(state: AddCardUi.Form, actions: AddCardActions, modifier: Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Step(
        actions::back, modifier, close = true,
        bottom = { NeoButton("Continuar", actions::next, enabled = state.canContinue && !state.checking, loading = state.checking) },
    ) {
        Text("Añadir tarjeta", style = MaterialTheme.typography.headlineMedium)
        // A blank card until the number tells the bank; then that bank's face, with the account's name and the expiry.
        if (state.card.art == CardArt.Neutral) ForeignCardFace(ForeignCardUi(state.card.id, state.number), Modifier.padding(vertical = NeoSpace.s))
        else BankCard(state.card, Modifier.padding(vertical = NeoSpace.s))
        NeoTextField(
            state.number, actions::number, "Número de tarjeta", Modifier.focusRequester(focus),
            keyboardType = KeyboardType.Number,
            error = state.numberError,
            // Always there, so the expiry does not move when the bank shows up.
            supporting = state.card.issuer.orEmpty(),
            visualTransformation = CardNumberTransformation,
            trailing = { NeoIconButton(Ds.drawable.ic_photo_camera, "Escanear", actions::scan, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        )
        NeoTextField(
            state.expiry, actions::expiry, "Vencimiento",
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
            error = state.expiryError,
            visualTransformation = ExpiryTransformation,
        )
    }
}

@Composable
private fun CellsStep(state: AddCardUi.Cells, actions: AddCardActions, modifier: Modifier) {
    SecureWindow()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Step(
        actions::back, modifier, close = false,
        bottom = { NeoButton("Añadir tarjeta", actions::send, enabled = state.canSend) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(NeoSpace.s)) {
            Text("Coordenadas y PIN", style = MaterialTheme.typography.headlineMedium)
            SeparatedText(state.card.line, MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.cells.forEachIndexed { index, cell ->
            NeoTextField(
                cell.value, { actions.cell(index, it) }, cell.label,
                if (index == 0) Modifier.focusRequester(focus) else Modifier,
                keyboardType = KeyboardType.NumberPassword,
                imeAction = if (index == state.cells.lastIndex) ImeAction.Done else ImeAction.Next,
                visualTransformation = PasswordVisualTransformation(),
            )
        }
    }
}

/** `BPA · CUP`, as far as the number typed tells. */
private val CardUi.issuer: String? get() = issuerOf(bank.ifEmpty { null }, currency.ifEmpty { null })

/** `9212 1245 0388 2709 · BPA · CUP`: the number on one line. */
private val CardUi.line: String get() = listOfNotNull(numberLine.unbroken(), issuer).joinToString(" · ")

/** A step of the form: ✕ or ← above, the fields scrolling, the action at the bottom. */
@Composable
private fun Step(
    onBack: () -> Unit, modifier: Modifier, close: Boolean,
    bottom: @Composable ColumnScope.() -> Unit, content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (close) NeoTopBar(onBack = onBack, backIcon = Ds.drawable.ic_close, backDescription = "Cerrar")
            else NeoTopBar(onBack = onBack)
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = NeoSpace.gutter),
                verticalArrangement = Arrangement.spacedBy(NeoSpace.l),
                content = content,
            )
            BottomAction(bottom)
        }
    }
}

/**
 * Where the send stands, laid out like a request of NeoTransfer's line: the mark at the same height while it travels
 * and once ENZONA answers, what happened under it, and one button at the bottom once it is not on its way. ✕ and the
 * button come with the answer; while the send travels their places stay empty, so the mark does not move.
 */
@Composable
private fun SendStatus(state: AddCardUi.Sent, actions: AddCardActions, modifier: Modifier) {
    val sending = state is AddCardUi.Sending
    val (tone, icon) = when (state) {
        is AddCardUi.Sending -> StatusTone.Pending to Ds.drawable.ic_add_card
        is AddCardUi.Added -> StatusTone.Positive to Ds.drawable.ic_add_card
        is AddCardUi.NotAdded -> when (state.kind) {
            NotAddedKind.Refused -> StatusTone.Danger to Ds.drawable.ic_close
            NotAddedKind.Failed -> StatusTone.Neutral to Ds.drawable.ic_close
            NotAddedKind.Unconfirmed -> StatusTone.Warning to Ds.drawable.ic_sync_problem
        }
    }
    val (title, body) = when (state) {
        is AddCardUi.Sending -> "Añadiendo la tarjeta…" to state.card.line
        is AddCardUi.Added -> "Tarjeta añadida" to "${state.card.numberLine.unbroken()} ya está en tu cuenta de ENZONA."
        is AddCardUi.NotAdded -> when (state.kind) {
            NotAddedKind.Refused -> "ENZONA no añadió la tarjeta"
            NotAddedKind.Failed -> "No se añadió la tarjeta"
            NotAddedKind.Unconfirmed -> "Sin confirmar"
        } to state.reason
    }
    val refused = state is AddCardUi.NotAdded && state.kind == NotAddedKind.Refused
    Scaffold(
        modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { NeoTopBar(onBack = if (sending) null else actions::done, backIcon = Ds.drawable.ic_close, backDescription = "Cerrar") },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val top = maxHeight * 0.16f
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = NeoSpace.gutter),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(top))
                    StatusMark(tone, icon, positive = { ConfirmedMark(112.dp) })
                    Spacer(Modifier.height(NeoSpace.xxl))
                    Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    SeparatedText(
                        body, MaterialTheme.typography.bodyLarge, Modifier.padding(top = NeoSpace.s),
                        color = if (refused) Neo.colors.danger else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(NeoSpace.xxl))
                }
            }
            BottomAction {
                when {
                    // The button's place, kept empty like ✕'s.
                    sending -> Spacer(Modifier.height(56.dp))
                    state is AddCardUi.NotAdded && state.kind != NotAddedKind.Unconfirmed -> NeoButton("Revisar los datos", actions::review)
                    else -> NeoButton("Volver a Ajustes", actions::done)
                }
            }
        }
    }
}
