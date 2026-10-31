package dev.duardo.neotransfer.plus.ui.transfer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.duardo.neotransfer.plus.designsystem.R as Ds
import dev.duardo.neotransfer.plus.designsystem.component.AmountDisplay
import dev.duardo.neotransfer.plus.designsystem.component.EmptyState
import dev.duardo.neotransfer.plus.designsystem.component.FittedText
import dev.duardo.neotransfer.plus.designsystem.component.GroupDivider
import dev.duardo.neotransfer.plus.designsystem.component.IconAvatar
import dev.duardo.neotransfer.plus.designsystem.component.KeypadKey
import dev.duardo.neotransfer.plus.designsystem.component.NameAvatar
import dev.duardo.neotransfer.plus.designsystem.component.NeoBottomSheet
import dev.duardo.neotransfer.plus.designsystem.component.NeoButton
import dev.duardo.neotransfer.plus.designsystem.component.NeoConfirmSheet
import dev.duardo.neotransfer.plus.designsystem.component.NeoIconButton
import dev.duardo.neotransfer.plus.designsystem.component.NeoKeypad
import dev.duardo.neotransfer.plus.designsystem.component.NeoRow
import dev.duardo.neotransfer.plus.designsystem.component.NeoSearchField
import dev.duardo.neotransfer.plus.designsystem.component.NeoTopBar
import dev.duardo.neotransfer.plus.designsystem.component.ReviewLayout
import dev.duardo.neotransfer.plus.designsystem.component.SectionHeader
import dev.duardo.neotransfer.plus.designsystem.component.SeparatedText
import dev.duardo.neotransfer.plus.designsystem.component.SummaryLine
import dev.duardo.neotransfer.plus.designsystem.component.groupItem
import dev.duardo.neotransfer.plus.designsystem.format.NoBreak
import dev.duardo.neotransfer.plus.designsystem.format.formatAmount
import dev.duardo.neotransfer.plus.designsystem.format.formatCardNumber
import dev.duardo.neotransfer.plus.designsystem.format.formatMoney
import dev.duardo.neotransfer.plus.designsystem.theme.Neo
import dev.duardo.neotransfer.plus.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.plus.ui.common.BottomAction
import dev.duardo.neotransfer.plus.ui.common.CardNumberTransformation
import dev.duardo.neotransfer.plus.ui.common.ForeignCardThumb
import dev.duardo.neotransfer.plus.ui.common.SourceCardPicker
import dev.duardo.neotransfer.plus.ui.common.numberLine
import dev.duardo.neotransfer.plus.ui.common.timeLabels
import dev.duardo.neotransfer.plus.ui.model.DuplicateUi
import dev.duardo.neotransfer.plus.ui.model.ContactUi
import dev.duardo.neotransfer.plus.ui.model.RecipientPickUi
import dev.duardo.neotransfer.plus.ui.model.RecipientUi
import dev.duardo.neotransfer.plus.ui.model.TransferAmountUi
import dev.duardo.neotransfer.plus.ui.model.TransferReviewUi

// ---- Recipient ----------------------------------------------------------------------------------------------

interface RecipientActions {
    fun back()
    fun query(value: String)
    fun scan()
    fun pickTyped()
    fun pick(recipient: RecipientUi.Card)
    fun pickContact(contactId: String)
    fun pickContactCard(contactId: String, cardId: String)
    fun closeCards()
}

/** Who receives: a contact, a recent recipient, a typed or scanned card number, or an ENZONA account's QR. */
@Composable
fun RecipientScreen(state: RecipientPickUi, actions: RecipientActions, modifier: Modifier = Modifier) {
    Scaffold(
        modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { NeoTopBar("Transferir", onBack = actions::back) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = NeoSpace.xxl),
        ) {
            item(key = "search") {
                Column(Modifier.padding(horizontal = NeoSpace.gutter), verticalArrangement = Arrangement.spacedBy(NeoSpace.s)) {
                    NeoSearchField(
                        state.query, actions::query, "Nombre o número de tarjeta",
                        emptyAction = { NeoIconButton(Ds.drawable.ic_photo_camera, "Escanear tarjeta o QR de cuenta", actions::scan, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        // A card number reads grouped as printed while it is typed.
                        visualTransformation = if (state.query.isNotEmpty() && state.query.all(Char::isDigit)) CardNumberTransformation else VisualTransformation.None,
                    )
                    state.typedError?.let { Text(it, Modifier.padding(start = NeoSpace.l), style = MaterialTheme.typography.bodyMedium, color = Neo.colors.danger) }
                }
            }
            if (state.recent.isNotEmpty()) item(key = "recent") {
                Column(Modifier.padding(top = NeoSpace.l)) {
                    SectionHeader("Recientes", Modifier.padding(horizontal = NeoSpace.gutter))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = NeoSpace.gutter),
                        horizontalArrangement = Arrangement.spacedBy(NeoSpace.s),
                    ) {
                        items(state.recent, key = { it.card.id }) { recipient -> RecentChip(recipient) { actions.pick(recipient) } }
                    }
                }
            }
            val typed = state.typed
            if (state.query.isBlank() && state.recent.isEmpty() && state.contacts.isEmpty()) item(key = "empty") {
                EmptyState(Ds.drawable.ic_contacts, "Sin contactos con tarjeta")
            }
            val count = state.contacts.size + if (typed != null) 1 else 0
            if (count > 0) {
                item(key = "contacts-title") {
                    if (state.contacts.isNotEmpty()) SectionHeader("Contactos", Modifier.padding(horizontal = NeoSpace.gutter).padding(top = NeoSpace.l))
                    else Spacer(Modifier.height(NeoSpace.l))
                }
                if (typed != null) item(key = "typed") {
                    Column(Modifier.padding(horizontal = NeoSpace.gutter).groupItem(0, count)) {
                        NeoRow(
                            formatCardNumber(typed.number),
                            supporting = listOfNotNull("Tarjeta nueva", typed.issuer).joinToString(" · "),
                            leading = { ForeignCardThumb(typed) },
                            onClick = actions::pickTyped,
                        )
                    }
                }
                val offset = if (typed != null) 1 else 0
                itemsIndexed(state.contacts, key = { _, it -> it.id }) { index, contact ->
                    Column(Modifier.padding(horizontal = NeoSpace.gutter).groupItem(index + offset, count)) {
                        if (index + offset > 0) GroupDivider()
                        NeoRow(
                            contact.name,
                            supporting = contact.cards.firstOrNull()?.let { first ->
                                formatCardNumber(first.number) + if (contact.cards.size > 1) " · ${contact.cards.size}${NoBreak}tarjetas" else ""
                            } ?: "Sin tarjeta guardada",
                            leading = { NameAvatar(contact.name) },
                            onClick = { actions.pickContact(contact.id) },
                        )
                    }
                }
            }
        }
    }
    state.chooseCardOf?.let { contact -> ContactCardsSheet(contact, actions) }
}

@Composable
private fun RecentChip(recipient: RecipientUi.Card, onClick: () -> Unit) {
    Column(
        Modifier.width(76.dp).clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = onClick).padding(vertical = NeoSpace.s),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NeoSpace.s),
    ) {
        val name = recipient.name
        if (name != null) NameAvatar(name, size = 52.dp) else IconAvatar(Ds.drawable.ic_credit_card, size = 52.dp)
        // A card without a name shows its whole number, in two lines of eight that shrink to fit.
        Text(
            name?.substringBefore(' ') ?: recipient.card.number.chunked(8).joinToString("\n", transform = ::formatCardNumber),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = MaterialTheme.typography.labelMedium.fontSize),
        )
    }
}

@Composable
private fun ContactCardsSheet(contact: ContactUi, actions: RecipientActions) {
    NeoBottomSheet(onDismissRequest = actions::closeCards, title = "Tarjetas de ${contact.name.substringBefore(' ')}") {
        Column(Modifier.padding(horizontal = NeoSpace.xs)) {
            contact.cards.forEach { card ->
                NeoRow(
                    formatCardNumber(card.number),
                    supporting = listOfNotNull(card.issuer, card.label).joinToString(" · ").ifEmpty { null },
                    leading = { ForeignCardThumb(card) },
                    onClick = { actions.pickContactCard(contact.id, card.id) },
                )
            }
        }
    }
}

// ---- Amount -------------------------------------------------------------------------------------------------

interface AmountActions {
    fun back()
    fun key(key: KeypadKey)
    fun source(index: Int)
    fun next()
}

/** Who receives the money, as one line: name and card or ENZONA account. */
private val RecipientUi.title: String
    get() = when (this) {
        is RecipientUi.Card -> name ?: formatCardNumber(card.number)
        is RecipientUi.Account -> name ?: username
    }

private val RecipientUi.detail: String?
    get() = when (this) {
        is RecipientUi.Card -> listOfNotNull(if (name != null) formatCardNumber(card.number) else null, card.issuer).joinToString(" · ").ifEmpty { null }
        is RecipientUi.Account -> if (name != null) "Cuenta ENZONA · $username" else "Cuenta ENZONA"
    }

@Composable
private fun RecipientAvatar(recipient: RecipientUi, size: Dp) {
    val name = recipient.name
    when {
        name != null -> NameAvatar(name, size = size)
        recipient is RecipientUi.Account -> IconAvatar(Ds.drawable.ic_account_balance_wallet, size = size)
        else -> IconAvatar(Ds.drawable.ic_credit_card, size = size)
    }
}

/** How much and from which card. Keypad on screen; the balance warns, it does not block: ENZONA decides. */
@Composable
fun TransferAmountScreen(state: TransferAmountUi, actions: AmountActions, modifier: Modifier = Modifier) {
    Scaffold(
        modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { NeoTopBar(onBack = actions::back) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Who on top, the amount in the middle of what is left, the paying card right over the keypad.
            Column(Modifier.weight(1f).padding(horizontal = NeoSpace.gutter), horizontalAlignment = Alignment.CenterHorizontally) {
                RecipientHeader(state.recipient, avatar = 48.dp)
                Spacer(Modifier.weight(1f))
                AmountDisplay(state.input, state.currency)
                Box(Modifier.height(NeoSpace.xxxl), contentAlignment = Alignment.Center) {
                    if (state.overBalance) Text("Más que el saldo de esta tarjeta", style = MaterialTheme.typography.bodyMedium, color = Neo.colors.warning)
                }
                Spacer(Modifier.weight(1f))
                SourceCardPicker(state.sources, state.source, actions::source)
            }
            if (!state.fixed) NeoKeypad(actions::key, Modifier.padding(horizontal = NeoSpace.m).padding(top = NeoSpace.m))
            BottomAction { NeoButton("Continuar", actions::next, enabled = state.canContinue) }
        }
    }
}

/** Who receives, centred: the name with the card or account it goes to ([detail]), or the bare card and its bank. */
@Composable
private fun RecipientHeader(recipient: RecipientUi, avatar: Dp, detail: Boolean = true) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RecipientAvatar(recipient, avatar)
        FittedText(
            recipient.title,
            MaterialTheme.typography.titleLarge,
            Modifier.padding(top = NeoSpace.m),
            textAlign = TextAlign.Center,
            minScale = 0.75f,
        )
        if (detail || recipient.name == null) recipient.detail?.let {
            SeparatedText(
                it, MaterialTheme.typography.bodyMedium, Modifier.padding(top = NeoSpace.xxs),
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
        }
    }
}

// ---- Review -------------------------------------------------------------------------------------------------

interface ReviewActions {
    fun back()
    fun confirm()
    fun confirmDuplicate()
    fun cancelDuplicate()
}

/** Last look before the fingerprint: what, to whom, from where. */
@Composable
fun TransferReviewScreen(state: TransferReviewUi, actions: ReviewActions, modifier: Modifier = Modifier) {
    val recipient = state.recipient
    Scaffold(
        modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { NeoTopBar("Revisar", onBack = actions::back) },
    ) { padding ->
        ReviewLayout(
            amount = state.amount.amount,
            currency = state.amount.currency,
            lines = listOfNotNull(
                when (recipient) {
                    is RecipientUi.Card -> recipient.name?.let { SummaryLine("Tarjeta destino", formatCardNumber(recipient.card.number), recipient.card.issuer) }
                    is RecipientUi.Account -> recipient.name?.let { SummaryLine("Cuenta ENZONA", recipient.username) }
                },
                SummaryLine("Desde", state.source.numberLine, state.source.bank),
            ),
            confirm = "Transferir ${formatMoney(state.amount.amount, state.amount.currency)}",
            onConfirm = actions::confirm,
            modifier = Modifier.padding(padding),
            confirmIcon = Ds.drawable.ic_fingerprint,
            confirming = state.sending,
            // The lines below already name the card or account of a known recipient.
            lead = { RecipientHeader(recipient, avatar = 56.dp, detail = false) },
        )
    }
    state.duplicate?.let { DuplicateSheet(it, actions::confirmDuplicate, actions::cancelDuplicate) }
}

/** Same card, same destination, same amount, still unconfirmed: ask, never block. */
@Composable
fun DuplicateSheet(duplicate: DuplicateUi, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val amount = "${formatAmount(duplicate.amount.amount).removeSuffix(".00")}$NoBreak${duplicate.amount.currency.uppercase()}"
    val first = duplicate.recipient.substringBefore(' ')
    val ago = timeLabels.ago(duplicate.sentAt).let { if (it == "ahora") "hace un momento" else it.replaceFirstChar(Char::lowercase) }
    NeoConfirmSheet(
        title = "¿Enviar otros $amount?",
        text = "Ya enviaste $amount a $first $ago y ENZONA aún no lo confirmó.",
        confirm = "Enviar otros $amount",
        dismiss = "Cancelar",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}
