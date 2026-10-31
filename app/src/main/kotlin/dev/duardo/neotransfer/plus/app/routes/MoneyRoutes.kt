package dev.duardo.neotransfer.plus.app.routes

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.duardo.neotransfer.plus.app.Banks
import dev.duardo.neotransfer.plus.app.Dest
import dev.duardo.neotransfer.plus.app.LocalAuthorizer
import dev.duardo.neotransfer.plus.app.PlusApp
import dev.duardo.neotransfer.plus.app.Authorizer
import dev.duardo.neotransfer.plus.app.ownerOf
import dev.duardo.neotransfer.plus.app.pendingTransfer
import dev.duardo.neotransfer.plus.app.recentRecipients
import dev.duardo.neotransfer.plus.app.toUi
import dev.duardo.neotransfer.plus.core.money.Amounts
import dev.duardo.neotransfer.plus.core.payment.EnzonaAccountTransferDraft
import dev.duardo.neotransfer.plus.core.payment.EnzonaCardTransferDraft
import dev.duardo.neotransfer.plus.core.payment.EnzonaIntentState
import dev.duardo.neotransfer.plus.core.payment.EnzonaPaymentDraft
import dev.duardo.neotransfer.plus.core.payment.EnzonaQrMerchantPaymentDraft
import dev.duardo.neotransfer.plus.core.qr.EnzonaQrCode
import dev.duardo.neotransfer.plus.core.qr.EnzonaOwnQr
import dev.duardo.neotransfer.plus.core.qr.EnzonaQrKind
import dev.duardo.neotransfer.plus.core.qr.EnzonaResolvedQr
import dev.duardo.neotransfer.plus.data.EnzonaFailure
import dev.duardo.neotransfer.plus.data.EnzonaResult
import dev.duardo.neotransfer.plus.data.contacts.Contact
import dev.duardo.neotransfer.plus.data.contacts.ContactCard
import dev.duardo.neotransfer.plus.data.enzona.EnzonaIntent
import dev.duardo.neotransfer.plus.designsystem.component.KeypadKey
import dev.duardo.neotransfer.plus.designsystem.format.formatCardNumber
import dev.duardo.neotransfer.plus.designsystem.format.formatMoney
import dev.duardo.neotransfer.plus.platform.copyText
import dev.duardo.neotransfer.plus.platform.shareQr
import dev.duardo.neotransfer.plus.scanner.QrVerdict
import dev.duardo.neotransfer.plus.scanner.ScanMode
import dev.duardo.neotransfer.plus.scanner.ScanResult
import dev.duardo.neotransfer.plus.scanner.ScannerView
import dev.duardo.neotransfer.plus.ui.model.BalanceUi
import dev.duardo.neotransfer.plus.ui.model.CounterpartUi
import dev.duardo.neotransfer.plus.ui.model.DuplicateUi
import dev.duardo.neotransfer.plus.ui.model.Money
import dev.duardo.neotransfer.plus.ui.model.OperationKind
import dev.duardo.neotransfer.plus.ui.model.OperationStatus
import dev.duardo.neotransfer.plus.ui.model.PayQrUi
import dev.duardo.neotransfer.plus.ui.model.PayReviewUi
import dev.duardo.neotransfer.plus.ui.model.PaymentResultUi
import dev.duardo.neotransfer.plus.ui.model.ReceiveUi
import dev.duardo.neotransfer.plus.ui.model.RecipientPickUi
import dev.duardo.neotransfer.plus.ui.model.RecipientUi
import dev.duardo.neotransfer.plus.ui.model.TransferAmountUi
import dev.duardo.neotransfer.plus.ui.model.TransferReviewUi
import dev.duardo.neotransfer.plus.ui.pay.PayQrActions
import dev.duardo.neotransfer.plus.ui.pay.PayQrScreen
import dev.duardo.neotransfer.plus.ui.payment.PaymentResultScreen
import dev.duardo.neotransfer.plus.ui.payment.ResultActions
import dev.duardo.neotransfer.plus.ui.receive.ReceiveActions
import dev.duardo.neotransfer.plus.ui.receive.ReceiveScreen
import dev.duardo.neotransfer.plus.ui.transfer.AmountActions
import dev.duardo.neotransfer.plus.ui.transfer.RecipientActions
import dev.duardo.neotransfer.plus.ui.transfer.RecipientScreen
import dev.duardo.neotransfer.plus.ui.transfer.ReviewActions
import dev.duardo.neotransfer.plus.ui.transfer.TransferAmountScreen
import dev.duardo.neotransfer.plus.ui.transfer.TransferReviewScreen
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** ENZONA's own code families go on to ENZONA; a card's transfer code goes to a transfer; anything else is refused. */
private fun classifyQr(code: String): QrVerdict = when {
    EnzonaQrCode.isCandidate(code) || cardFromQr(code) != null -> QrVerdict.Accepted
    else -> QrVerdict.Rejected("Este QR no es de ENZONA ni de una tarjeta")
}

/** A transfer to [pan], named by its contact when exactly one has it. */
private fun cardRecipient(pan: String, contacts: List<Contact>): RecipientUi.Card {
    val contact = contacts.ownerOf(pan)
    val card = contact?.cards?.first { it.pan == pan }
    return RecipientUi.Card(Banks.foreignCard(card?.id ?: "pan:$pan", pan, card?.label), contact?.name, contact?.id)
}

private fun EnzonaResolvedQr.accountRecipient(): RecipientUi.Account? {
    val uuid = accountUuid ?: return null
    val username = username ?: return null
    return RecipientUi.Account(uuid, username, name)
}

// ---- One payment --------------------------------------------------------------------------------------------

/**
 * Prepares the reviewed values once (the same intent is reused while the review does not change), asks the
 * fingerprint for the password and sends exactly once; the result screen takes the review's place.
 */
class Payment(private val plus: PlusApp, private val authorizer: Authorizer) {
    var busy by mutableStateOf(false)
        private set
    private var prepared: Pair<EnzonaPaymentDraft, EnzonaIntent>? = null

    fun pay(draft: EnzonaPaymentDraft, title: String, subtitle: String?, firstTimeAction: String, name: String?) {
        if (busy) return
        busy = true
        plus.scope.launch {
            val intent = prepared?.takeIf { it.first == draft }?.second ?: try {
                plus.prepare(draft).also { prepared = draft to it }
            } catch (_: Exception) {
                busy = false
                plus.say("No se pudo preparar el pago.")
                return@launch
            }
            busy = false
            authorizer.authorize(title, subtitle, firstTimeAction, onPassword = { password, sealed ->
                if (name != null) plus.titles.update { it + (intent.id to name) }
                plus.submit(intent, password, sealed)
                plus.nav.replace(Dest.Result(intent.id))
            }, onProblem = plus::say)
        }
    }
}

@Composable
private fun rememberPayment(plus: PlusApp): Payment {
    val authorizer = LocalAuthorizer.current
    return remember(plus, authorizer) { Payment(plus, authorizer) }
}

// ---- Transfer -----------------------------------------------------------------------------------------------

@Composable
fun RecipientRoute(plus: PlusApp) {
    val contacts by plus.contactList.collectAsState()
    val journal by plus.journal.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var chooseCardOf by rememberSaveable { mutableStateOf<String?>(null) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    val all = contacts.orEmpty()
    val digits = query.filter(Char::isDigit)
    val numberQuery = digits.length == query.count { !it.isWhitespace() } && digits.isNotEmpty()
    val owner = if (digits.length == 16) all.ownerOf(digits) else null
    val typed = if (numberQuery && digits.length == 16 && luhn(digits) && owner == null) Banks.foreignCard("typed", digits) else null
    val typedError = if (numberQuery && digits.length == 16 && !luhn(digits)) "Número no válido: revisa los dígitos" else null
    // People with a card; while searching by name, the rest of the address book too: their card is asked for when chosen.
    val shown = all.filter { if (it.cards.isNotEmpty()) it.matches(query) else query.isNotBlank() && !numberQuery && it.matchesName(query) }
        .sortedBy { it.cards.isEmpty() }.map { it.toUi() }
    val state = RecipientPickUi(
        query, typed, typedError,
        recent = if (query.isEmpty()) recentRecipients(journal.orEmpty(), all) else emptyList(),
        contacts = shown,
        chooseCardOf = chooseCardOf?.let { id -> all.firstOrNull { it.id == id }?.toUi() },
    )

    fun toCard(pan: String) = plus.nav.push(Dest.Amount(cardRecipient(pan, all)))

    val actions = object : RecipientActions {
        override fun back() { plus.nav.back() }
        override fun query(value: String) { query = value }
        override fun scan() { scanning = true }
        override fun pickTyped() { typed?.let { toCard(it.number) } }
        override fun pick(recipient: RecipientUi.Card) = plus.nav.push(Dest.Amount(recipient))
        override fun pickContact(contactId: String) {
            val contact = all.firstOrNull { it.id == contactId } ?: return
            when (contact.cards.size) {
                0 -> plus.nav.push(Dest.CardEdit(contactId, thenTransfer = true))
                1 -> toCard(contact.cards.single().pan)
                else -> chooseCardOf = contactId
            }
        }
        override fun pickContactCard(contactId: String, cardId: String) {
            chooseCardOf = null
            all.firstOrNull { it.id == contactId }?.cards?.firstOrNull { it.id == cardId }?.let { toCard(it.pan) }
        }
        override fun closeCards() { chooseCardOf = null }
    }
    RecipientScreen(state, actions)
    if (scanning) {
        BackHandler { scanning = false }
        ScannerView(
            mode = ScanMode.ANY,
            classifyQr = ::classifyQr,
            onResult = { result ->
                when (result) {
                    is ScanResult.CardNumber -> { scanning = false; toCard(result.digits) }
                    // A card's code goes straight to the amount; an account or merchant QR is resolved where it can be paid.
                    is ScanResult.Qr -> {
                        scanning = false
                        cardFromQr(result.raw)?.let(::toCard) ?: plus.nav.push(Dest.Pay(result.raw))
                    }
                    is ScanResult.PhoneNumber -> plus.say("Las transferencias van a una tarjeta o a una cuenta ENZONA")
                }
            },
            onClose = { scanning = false },
        )
    }
}

@Composable
fun AmountRoute(plus: PlusApp, dest: Dest.Amount) {
    val wallet = rememberWallet(plus)
    val loaded by plus.wallet.collectAsState()
    val selected by plus.selectedCard.collectAsState()
    var input by rememberSaveable { mutableStateOf(dest.amount?.stripTrailingZeros()?.toPlainString().orEmpty()) }
    var source by rememberSaveable { mutableIntStateOf(selected) }
    val noCards = loaded?.isEmpty() == true
    LaunchedEffect(noCards) {
        if (noCards) {
            plus.say("No hay tarjetas vinculadas en ENZONA")
            plus.nav.back()
        }
    }
    if (wallet.isEmpty()) return
    val index = source.coerceIn(0, wallet.lastIndex)
    val card = wallet[index]
    val amount = Amounts.parse(input)
    val balance = (card.balance as? BalanceUi.Known)?.money?.amount
    val actions = object : AmountActions {
        override fun back() { plus.nav.back() }
        override fun key(key: KeypadKey) { input = typeAmount(input, key) }
        override fun source(index: Int) { source = index }
        override fun next() {
            val value = amount ?: return
            plus.nav.push(Dest.Review(dest.recipient, value, card.card.id, dest.description))
        }
    }
    TransferAmountScreen(
        TransferAmountUi(
            dest.recipient, input, card.card.currency, wallet, index,
            overBalance = amount != null && balance != null && amount > balance,
            canContinue = amount != null,
            fixed = dest.fixed,
        ),
        actions,
    )
}

@Composable
fun ReviewRoute(plus: PlusApp, dest: Dest.Review) {
    val wallet = rememberWallet(plus)
    val payment = rememberPayment(plus)
    val journal by plus.journal.collectAsState()
    val transactions by plus.transactions.collectAsState()
    var duplicate by remember { mutableStateOf<DuplicateUi?>(null) }
    val card = wallet.firstOrNull { it.card.id == dest.cardId }?.card ?: return
    val money = Money(dest.amount, card.currency)
    val recipient = dest.recipient
    val actions = object : ReviewActions {
        override fun back() { plus.nav.back() }
        override fun confirm() {
            val destination = when (recipient) {
                is RecipientUi.Card -> recipient.card.number
                is RecipientUi.Account -> recipient.uuid
            }
            val known = transactions.orEmpty().map { it.transactionUuid }.toSet()
            val pending = pendingTransfer(journal.orEmpty(), known, card.id, destination, dest.amount, card.currency)
            if (pending == null) send() else duplicate = DuplicateUi(money, recipient.to, Instant.ofEpochMilli(pending.intent.preparedAt))
        }
        override fun confirmDuplicate() {
            duplicate = null
            send()
        }
        override fun cancelDuplicate() { duplicate = null }

        private fun send() {
            val draft = when (recipient) {
                is RecipientUi.Card -> EnzonaCardTransferDraft(card.id, recipient.card.number, dest.amount, card.currency, dest.description)
                is RecipientUi.Account -> EnzonaAccountTransferDraft(card.id, recipient.uuid, recipient.username, dest.amount, card.currency, dest.description)
            }
            payment.pay(
                draft, "Transferir ${formatMoney(money.amount, money.currency)}", "a ${recipient.to}", "Guardar y transferir",
                name = (recipient as? RecipientUi.Account)?.let { it.name ?: it.username },
            )
        }
    }
    TransferReviewScreen(TransferReviewUi(recipient, money, card, sending = payment.busy, duplicate = duplicate), actions)
}

/** Who a transfer goes to, in one line: the name, or else the card or the account. */
private val RecipientUi.to: String
    get() = when (this) {
        is RecipientUi.Card -> name ?: formatCardNumber(card.number)
        is RecipientUi.Account -> name ?: username
    }

@Composable
fun ResultRoute(plus: PlusApp, intentId: String) {
    val operation = rememberOperation(plus, "j:$intentId") ?: return
    val contacts by plus.contactList.collectAsState()
    val journal by plus.journal.collectAsState()
    val authorizer = LocalAuthorizer.current
    val pan = (operation.counterpart as? CounterpartUi.Card)?.number
    val canSave = operation.kind == OperationKind.CardTransfer && operation.status == OperationStatus.Confirmed &&
        pan != null && contacts.orEmpty().none { contact -> contact.cards.any { it.pan == pan } }
    val actions = object : ResultActions {
        override fun retry() {
            val entry = journal?.firstOrNull { it.intent.id == intentId } ?: return
            val money = operation.amount?.let { formatMoney(it.amount, it.currency) }.orEmpty()
            when (entry.state) {
                // Nothing was dispatched: the reviewed intent is still unused, so it may be sent once, with a new approval.
                EnzonaIntentState.PREPARED -> authorizer.authorize("Enviar $money", operation.title, "Guardar y enviar",
                    onPassword = { password, sealed -> plus.submit(entry.intent, password, sealed) }, onProblem = plus::say)
                // Its one dispatch never reached ENZONA: a new intent with the same values, a new approval, its own result.
                EnzonaIntentState.NOT_DELIVERED -> plus.scope.launch {
                    val again = try { plus.prepareAgain(entry.intent) } catch (_: Exception) {
                        plus.say("No se pudo preparar el pago.")
                        return@launch
                    }
                    authorizer.authorize("Enviar $money", operation.title, "Guardar y enviar", onPassword = { password, sealed ->
                        plus.submit(again, password, sealed)
                        plus.nav.replace(Dest.Result(again.id))
                    }, onProblem = plus::say)
                }
                else -> Unit
            }
        }
        override fun changePassword() = plus.nav.push(Dest.PasswordSettings)
        override fun done() = plus.nav.home()
        override fun open() = plus.nav.replace(Dest.Operation("j:$intentId"))
        override fun saveContact() {
            val number = pan ?: return
            plus.nav.push(Dest.ContactEdit(null, Contact("new", "", listOf(ContactCard(UUID.randomUUID().toString(), number, null)), emptyList())))
        }
    }
    // The payment goes on by itself: back leaves the flow, it never returns to the review.
    BackHandler { plus.nav.home() }
    PaymentResultScreen(PaymentResultUi(operation, canSave), actions)
}

// ---- Pay a merchant QR --------------------------------------------------------------------------------------

@Composable
fun PayRoute(plus: PlusApp, code: String?) {
    val wallet = rememberWallet(plus)
    val payment = rememberPayment(plus)
    val selected by plus.selectedCard.collectAsState()
    var resolved by remember { mutableStateOf<EnzonaResolvedQr?>(null) }
    var resolving by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var amount by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf(wallet.getOrNull(selected)?.card?.id) }

    fun resolve(raw: String) {
        cardFromQr(raw)?.let { pan ->
            plus.nav.replace(Dest.Amount(cardRecipient(pan, plus.contactList.value.orEmpty())))
            return
        }
        if (resolving) return
        resolving = true
        problem = null
        plus.scope.launch {
            when (val result = plus.repository.resolveQr(raw)) {
                is EnzonaResult.Ok -> {
                    val qr = result.value
                    when (qr.kind) {
                        EnzonaQrKind.MERCHANT_PAYMENT -> {
                            resolved = qr
                            amount = qr.amount?.stripTrailingZeros()?.toPlainString().orEmpty()
                            description = qr.description.orEmpty()
                        }
                        EnzonaQrKind.ACCOUNT -> {
                            val recipient = qr.accountRecipient()
                            if (recipient == null) problem = "Este QR de cuenta no trae los datos para transferir"
                            else plus.nav.replace(Dest.Amount(recipient, qr.amount, fixed = qr.amount != null, description = qr.description))
                        }
                        EnzonaQrKind.MERCHANT_INFO -> problem = "Este QR no es de pago"
                    }
                }
                is EnzonaResult.Failed -> problem = when (result.reason) {
                    EnzonaFailure.INVALID_INPUT -> "Este QR no es de ENZONA"
                    EnzonaFailure.REJECTED -> result.serviceFault?.description ?: "ENZONA no reconoce este código"
                    else -> result.reason.message()
                }
            }
            resolving = false
        }
    }
    LaunchedEffect(code) { if (code != null) resolve(code) }

    val qr = resolved
    // A code in a currency is paid from a card in that currency.
    val sources = qr?.currency?.let { currency -> wallet.filter { it.card.currency.equals(currency, ignoreCase = true) } } ?: wallet
    val index = sources.indexOfFirst { it.card.id == source }.coerceAtLeast(0)
    val card = sources.getOrNull(index)?.card
    val value = if (qr?.amount != null) qr.amount else Amounts.parse(amount)
    val state = when {
        resolving -> PayQrUi.Resolving
        problem != null -> PayQrUi.Unsupported(problem!!)
        qr != null && card != null -> PayQrUi.Review(
            PayReviewUi(
                qr.name ?: "Comercio", amount, qr.amount == null, description, qr.currency ?: card.currency, sources, index,
                canPay = value != null && value.signum() > 0, paying = payment.busy,
            ),
        )
        qr != null -> PayQrUi.Unsupported(
            qr.currency?.let { "Para pagar este código hace falta una tarjeta en ${it.uppercase()}" } ?: "Para pagar hace falta una tarjeta",
            addCard = true,
        )
        else -> PayQrUi.Scanning
    }
    val actions = object : PayQrActions {
        override fun close() { plus.nav.back() }
        override fun addCard() = plus.nav.push(Dest.AddCard)
        override fun amount(value: String) { amount = value.filter { it.isDigit() || it == '.' || it == ',' }.take(12) }
        override fun description(value: String) { description = value }
        override fun source(index: Int) { source = sources.getOrNull(index)?.card?.id }
        override fun pay() {
            val paid = value ?: return
            val from = card ?: return
            val merchant = qr ?: return
            val currency = merchant.currency ?: from.currency
            payment.pay(
                EnzonaQrMerchantPaymentDraft(from.id, merchant.code, paid, currency, description.trim().ifEmpty { null }),
                "Pagar ${formatMoney(paid, currency)}", merchant.name?.let { "a $it" }, "Guardar y pagar",
                name = merchant.name,
            )
        }
        override fun rescan() {
            resolved = null
            problem = null
        }
    }
    BackHandler(state !is PayQrUi.Scanning && !resolving) { resolved = null; problem = null }
    PayQrScreen(state, actions, cameraContent = {
        ScannerView(
            mode = ScanMode.QR,
            classifyQr = ::classifyQr,
            onResult = { result -> if (result is ScanResult.Qr && resolved == null && problem == null) resolve(result.raw) },
        )
    })
}

// ---- Receive ------------------------------------------------------------------------------------------------

/** The account's code at once, without an amount; «Pedir importe» asks ENZONA for one with it, «Quitar importe» goes back. */
@Composable
fun ReceiveRoute(plus: PlusApp) {
    val context = LocalContext.current
    val account by plus.account.collectAsState()
    val currency = account?.primaryCurrency ?: "CUP"
    val owner = account?.let { listOfNotNull(it.name, it.lastName).joinToString(" ").ifEmpty { null } }
    var plain by remember { mutableStateOf<EnzonaOwnQr?>(null) }
    var priced by remember { mutableStateOf<EnzonaOwnQr?>(null) }
    /** The amount asked for; null while the code goes without one. */
    var requested by remember { mutableStateOf<BigDecimal?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var asking by rememberSaveable { mutableStateOf<String?>(null) }

    fun issue(amount: BigDecimal?) {
        error = null
        plus.scope.launch {
            val result = plus.repository.createOwnQr(amount, currency, null)
            // An answer for an amount no longer asked for is dropped.
            if (requested != amount) return@launch
            when (result) {
                is EnzonaResult.Ok -> if (amount == null) plain = result.value else priced = result.value
                is EnzonaResult.Failed -> error = result.reason.message()
            }
        }
    }
    LaunchedEffect(Unit) { if (plain == null) issue(null) }

    val shown = if (requested == null) plain else priced
    val actions = object : ReceiveActions {
        override fun back() { plus.nav.back() }
        override fun requestAmount() { asking = "" }
        override fun amount(value: String) { asking = value.filter { it.isDigit() || it == '.' || it == ',' }.take(12) }
        override fun showAmount() {
            val amount = Amounts.parse(asking.orEmpty()) ?: return
            asking = null
            requested = amount
            priced = null
            issue(amount)
        }
        override fun closeAmount() { asking = null }
        override fun clearAmount() {
            requested = null
            priced = null
            error = null
            if (plain == null) issue(null)
        }
        override fun retry() = issue(requested)
        override fun share() {
            val code = shown ?: return
            val money = requested?.let { formatMoney(code.amount ?: it, code.currency ?: currency) }
            context.shareQr(code.code, money?.let { "Cobro de $it" } ?: "Cobro con ENZONA")
        }
        override fun copyAccount() {
            account?.username?.let { context.copyText("Cuenta ENZONA", it) }
        }
    }
    ReceiveScreen(
        ReceiveUi(
            payload = shown?.code,
            amount = requested?.let { Money(priced?.amount ?: it, priced?.currency ?: currency) },
            currency = currency,
            owner = owner,
            username = account?.username,
            error = error,
            asking = asking,
        ),
        actions,
    )
}

// ---- Universal scanner --------------------------------------------------------------------------------------

/** The shortcut and the tile: a QR goes to pay or transfer, a card number to transfer, a mobile to a new contact. */
@Composable
fun ScanRoute(plus: PlusApp) {
    val contacts by plus.contactList.collectAsState()
    ScannerView(
        mode = ScanMode.ANY,
        classifyQr = ::classifyQr,
        onResult = { result ->
            when (result) {
                is ScanResult.Qr -> plus.nav.replace(
                    cardFromQr(result.raw)?.let { Dest.Amount(cardRecipient(it, contacts.orEmpty())) } ?: Dest.Pay(result.raw),
                )
                is ScanResult.CardNumber -> plus.nav.replace(Dest.Amount(cardRecipient(result.digits, contacts.orEmpty())))
                is ScanResult.PhoneNumber -> plus.nav.replace(Dest.ContactEdit(null, Contact("new", "", emptyList(), listOf(result.digits))))
            }
        },
        onClose = { plus.nav.back() },
    )
}
