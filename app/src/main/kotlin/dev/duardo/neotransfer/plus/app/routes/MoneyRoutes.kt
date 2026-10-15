package dev.duardo.neotransfer.plus.app.routes

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import dev.duardo.neotransfer.plus.app.recentRecipients
import dev.duardo.neotransfer.plus.app.toUi
import dev.duardo.neotransfer.plus.core.money.Amounts
import dev.duardo.neotransfer.plus.core.payment.EnzonaAccountTransferDraft
import dev.duardo.neotransfer.plus.core.payment.EnzonaCardTransferDraft
import dev.duardo.neotransfer.plus.core.payment.EnzonaPaymentDraft
import dev.duardo.neotransfer.plus.core.payment.EnzonaQrMerchantPaymentDraft
import dev.duardo.neotransfer.plus.core.qr.EnzonaQrCode
import dev.duardo.neotransfer.plus.core.qr.EnzonaQrKind
import dev.duardo.neotransfer.plus.core.qr.EnzonaResolvedQr
import dev.duardo.neotransfer.plus.data.EnzonaFailure
import dev.duardo.neotransfer.plus.data.EnzonaResult
import dev.duardo.neotransfer.plus.data.contacts.Contact
import dev.duardo.neotransfer.plus.data.contacts.ContactCard
import dev.duardo.neotransfer.plus.designsystem.component.KeypadKey
import dev.duardo.neotransfer.plus.designsystem.format.formatCardNumber
import dev.duardo.neotransfer.plus.designsystem.format.formatMoney
import dev.duardo.neotransfer.plus.platform.shareQr
import dev.duardo.neotransfer.plus.scanner.QrVerdict
import dev.duardo.neotransfer.plus.scanner.ScanMode
import dev.duardo.neotransfer.plus.scanner.ScanResult
import dev.duardo.neotransfer.plus.scanner.ScannerView
import dev.duardo.neotransfer.plus.ui.model.BalanceUi
import dev.duardo.neotransfer.plus.ui.model.ForeignCardUi
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Only ENZONA's own code families go on to ENZONA; anything else is refused over the camera. */
private fun classifyEnzona(code: String): QrVerdict =
    if (EnzonaQrCode.isCandidate(code)) QrVerdict.Accepted else QrVerdict.Rejected("Este QR no es de ENZONA")

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
    private var prepared: Pair<EnzonaPaymentDraft, dev.duardo.neotransfer.plus.data.enzona.EnzonaIntent>? = null

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
            authorizer.authorize(title, subtitle, firstTimeAction, onPassword = { password ->
                if (name != null) plus.titles.update { it + (intent.id to name) }
                plus.submit(intent, password)
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
    val typed = if (numberQuery && digits.length == 16 && luhn(digits) && owner == null) ForeignCardUi("typed", digits, Banks.ofPan(digits)) else null
    val typedError = if (numberQuery && digits.length == 16 && !luhn(digits)) "Número no válido: revisa los dígitos" else null
    val withCards = all.filter { it.cards.isNotEmpty() && it.matches(query) }.map(Contact::toUi)
    val state = RecipientPickUi(
        query, typed, typedError,
        recent = if (query.isEmpty()) recentRecipients(journal, all) else emptyList(),
        contacts = withCards,
        chooseCardOf = chooseCardOf?.let { id -> withCards.firstOrNull { it.id == id } ?: all.firstOrNull { it.id == id }?.toUi() },
    )

    fun toCard(pan: String) {
        val contact = all.ownerOf(pan)
        val card = contact?.cards?.first { it.pan == pan }
        plus.nav.push(Dest.Amount(RecipientUi.Card(ForeignCardUi(card?.id ?: "pan:$pan", pan, Banks.ofPan(pan), card?.label), contact?.name, contact?.id)))
    }

    val actions = object : RecipientActions {
        override fun back() { plus.nav.back() }
        override fun query(value: String) { query = value }
        override fun scan() { scanning = true }
        override fun pickTyped() { typed?.let { toCard(it.number) } }
        override fun pick(recipient: RecipientUi.Card) = plus.nav.push(Dest.Amount(recipient))
        override fun pickContact(contactId: String) {
            val contact = all.firstOrNull { it.id == contactId } ?: return
            if (contact.cards.size == 1) toCard(contact.cards.single().pan) else chooseCardOf = contactId
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
            classifyQr = ::classifyEnzona,
            onResult = { result ->
                when (result) {
                    is ScanResult.CardNumber -> { scanning = false; toCard(result.digits) }
                    // An account or merchant QR is resolved where it can be paid.
                    is ScanResult.Qr -> { scanning = false; plus.nav.push(Dest.Pay(result.raw)) }
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
    var source by rememberSaveable { mutableStateOf(selected) }
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
    val card = wallet.firstOrNull { it.card.id == dest.cardId }?.card ?: return
    val money = Money(dest.amount, card.currency)
    val recipient = dest.recipient
    val actions = object : ReviewActions {
        override fun back() { plus.nav.back() }
        override fun confirm() {
            val draft = when (recipient) {
                is RecipientUi.Card -> EnzonaCardTransferDraft(card.id, recipient.card.number, dest.amount, card.currency, dest.description)
                is RecipientUi.Account -> EnzonaAccountTransferDraft(card.id, recipient.uuid, recipient.username, dest.amount, card.currency, dest.description)
            }
            val to = when (recipient) {
                is RecipientUi.Card -> recipient.name ?: formatCardNumber(recipient.card.number)
                is RecipientUi.Account -> recipient.name ?: recipient.username
            }
            payment.pay(
                draft, "Transferir ${formatMoney(money.amount, money.currency)}", "a $to", "Guardar y transferir",
                name = (recipient as? RecipientUi.Account)?.let { it.name ?: it.username },
            )
        }
    }
    TransferReviewScreen(TransferReviewUi(recipient, money, card, sending = payment.busy), actions)
}

@Composable
fun ResultRoute(plus: PlusApp, intentId: String) {
    val operation = rememberOperation(plus, "j:$intentId") ?: return
    val contacts by plus.contactList.collectAsState()
    val pan = (operation.counterpart as? dev.duardo.neotransfer.plus.ui.model.CounterpartUi.Card)?.number
    val canSave = operation.kind == OperationKind.CardTransfer && operation.status == OperationStatus.Confirmed &&
        pan != null && contacts.orEmpty().none { contact -> contact.cards.any { it.pan == pan } }
    val actions = object : ResultActions {
        override fun done() = plus.nav.home()
        override fun open() = plus.nav.replace(Dest.Operation("j:$intentId"))
        override fun saveContact() {
            val number = pan ?: return
            plus.nav.push(Dest.ContactEdit(null, Contact("new", "", listOf(ContactCard(java.util.UUID.randomUUID().toString(), number, null)), emptyList())))
        }
    }
    BackHandler(operation.status != OperationStatus.Sending) { plus.nav.home() }
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
    var source by rememberSaveable { mutableStateOf(selected) }

    fun resolve(raw: String) {
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
    val index = source.coerceIn(0, (wallet.size - 1).coerceAtLeast(0))
    val card = wallet.getOrNull(index)?.card
    val value = if (qr?.amount != null) qr.amount else Amounts.parse(amount)
    val state = when {
        resolving -> PayQrUi.Resolving
        problem != null -> PayQrUi.Unsupported(problem!!)
        qr != null && card != null -> PayQrUi.Review(
            PayReviewUi(
                qr.name ?: "Comercio", amount, qr.amount == null, description, qr.currency ?: card.currency, wallet, index,
                canPay = value != null && value.signum() > 0, paying = payment.busy,
            ),
        )
        qr != null -> PayQrUi.Unsupported("No hay tarjetas en ENZONA para pagar")
        else -> PayQrUi.Scanning
    }
    val actions = object : PayQrActions {
        override fun amount(value: String) { amount = value }
        override fun description(value: String) { description = value }
        override fun source(index: Int) { source = index }
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
            classifyQr = ::classifyEnzona,
            onResult = { result -> if (result is ScanResult.Qr && resolved == null && problem == null) resolve(result.raw) },
            onClose = { plus.nav.back() },
        )
    })
}

// ---- Receive ------------------------------------------------------------------------------------------------

@Composable
fun ReceiveRoute(plus: PlusApp) {
    val context = LocalContext.current
    val account by plus.account.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf<ReceiveUi.Code?>(null) }
    val currency = account?.primaryCurrency ?: "CUP"
    val owner = account?.let { listOfNotNull(it.name, it.lastName).joinToString(" ").ifEmpty { null } }
    val actions = object : ReceiveActions {
        override fun back() { plus.nav.back() }
        override fun key(key: KeypadKey) { input = typeAmount(input, key); error = null }
        override fun create() {
            val amount = Amounts.parse(input) ?: return
            creating = true
            plus.scope.launch {
                when (val result = plus.repository.createOwnQr(amount, currency, null)) {
                    is EnzonaResult.Ok -> code = ReceiveUi.Code(result.value.code, Money(result.value.amount ?: amount, result.value.currency ?: currency), owner)
                    is EnzonaResult.Failed -> error = result.reason.message()
                }
                creating = false
            }
        }
        override fun otherAmount() { code = null }
        override fun share() {
            val current = code ?: return
            context.shareQr(current.payload, "Cobro de ${formatMoney(current.amount.amount, current.amount.currency)}")
        }
    }
    BackHandler(code != null) { code = null }
    ReceiveScreen(code ?: ReceiveUi.Amount(input, currency, creating, error), actions)
}

// ---- Universal scanner --------------------------------------------------------------------------------------

/** The shortcut and the tile: a QR goes to pay or transfer, a card number to transfer, a mobile to a new contact. */
@Composable
fun ScanRoute(plus: PlusApp) {
    val contacts by plus.contactList.collectAsState()
    ScannerView(
        mode = ScanMode.ANY,
        classifyQr = ::classifyEnzona,
        onResult = { result ->
            when (result) {
                is ScanResult.Qr -> plus.nav.replace(Dest.Pay(result.raw))
                is ScanResult.CardNumber -> {
                    val pan = result.digits
                    val contact = contacts.orEmpty().ownerOf(pan)
                    val card = contact?.cards?.first { it.pan == pan }
                    plus.nav.replace(Dest.Amount(RecipientUi.Card(ForeignCardUi(card?.id ?: "pan:$pan", pan, Banks.ofPan(pan), card?.label), contact?.name, contact?.id)))
                }
                is ScanResult.PhoneNumber -> plus.nav.replace(Dest.ContactEdit(null, Contact("new", "", emptyList(), listOf(result.digits))))
            }
        },
        onClose = { plus.nav.back() },
    )
}
