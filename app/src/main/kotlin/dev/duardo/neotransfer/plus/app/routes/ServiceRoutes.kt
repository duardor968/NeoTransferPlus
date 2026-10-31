package dev.duardo.neotransfer.plus.app.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.duardo.neotransfer.plus.app.Dest
import dev.duardo.neotransfer.plus.app.LocalAuthorizer
import dev.duardo.neotransfer.plus.app.PlusApp
import dev.duardo.neotransfer.plus.app.invoiceService
import dev.duardo.neotransfer.plus.app.referenceKey
import dev.duardo.neotransfer.plus.core.money.Amounts
import dev.duardo.neotransfer.plus.core.payment.EnzonaInvoicePaymentDraft
import dev.duardo.neotransfer.plus.core.service.EnzonaElectricityBill
import dev.duardo.neotransfer.plus.core.service.EnzonaEtecsaAccount
import dev.duardo.neotransfer.plus.data.EnzonaFailure
import dev.duardo.neotransfer.plus.data.EnzonaResult
import dev.duardo.neotransfer.plus.designsystem.format.formatMoney
import dev.duardo.neotransfer.plus.ui.model.BillUi
import dev.duardo.neotransfer.plus.ui.model.Money
import dev.duardo.neotransfer.plus.ui.model.SavedEditUi
import dev.duardo.neotransfer.plus.ui.model.SavedReferenceUi
import dev.duardo.neotransfer.plus.ui.model.ServiceFormUi
import dev.duardo.neotransfer.plus.ui.model.ServiceKind
import dev.duardo.neotransfer.plus.ui.model.ServiceLookupUi
import dev.duardo.neotransfer.plus.ui.model.ServicesUi
import dev.duardo.neotransfer.plus.ui.services.ServiceFormActions
import dev.duardo.neotransfer.plus.ui.services.ServiceFormScreen
import dev.duardo.neotransfer.plus.ui.services.ServicesActions
import dev.duardo.neotransfer.plus.ui.services.ServicesScreen
import dev.duardo.neotransfer.plus.ui.services.domain
import kotlinx.coroutines.launch

@Composable
fun ServicesRoute(plus: PlusApp) {
    val saved by plus.saved.collectAsState()
    val actions = remember(plus) {
        object : ServicesActions {
            override fun open(kind: ServiceKind) = plus.nav.push(Dest.Service(kind))
            override fun pay(reference: SavedReferenceUi) = plus.nav.push(Dest.Service(reference.kind, reference.value))
        }
    }
    ServicesScreen(ServicesUi(saved), actions)
}

/** What ENZONA answered, kept with the identifiers a payment needs. */
private sealed interface Answer {
    data class Bills(val owner: String?, val bills: List<EnzonaElectricityBill>) : Answer
    data class Etecsa(val owner: String?, val account: EnzonaEtecsaAccount) : Answer
}

/** The identifier ENZONA expects: digits for codes and phones, the full Nauta account with its domain. */
private fun ServiceKind.normalize(reference: String): String {
    val value = reference.trim()
    return when {
        this == ServiceKind.Electricity || this == ServiceKind.Landline -> value.filter(Char::isDigit)
        '@' !in value -> value + domain.orEmpty()
        else -> value
    }
}

private fun EnzonaFailure.lookupMessage(id: String): String = when (this) {
    EnzonaFailure.REJECTED, EnzonaFailure.INVALID_INPUT -> "ENZONA no encontró $id"
    else -> message()
}

@Composable
fun ServiceRoute(plus: PlusApp, dest: Dest.Service) {
    val kind = dest.kind
    val wallet = rememberWallet(plus)
    val selected by plus.selectedCard.collectAsState()
    val authorizer = LocalAuthorizer.current
    val payment = remember(plus, authorizer) { Payment(plus, authorizer) }
    val saved by plus.saved.collectAsState()
    // A saved Nauta account comes with its domain; the field shows the domain apart.
    var reference by rememberSaveable { mutableStateOf(kind.domain?.let(dest.reference::removeSuffix) ?: dest.reference) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by rememberSaveable { mutableStateOf(false) }
    var amount by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableIntStateOf(selected) }
    var bill by rememberSaveable { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var answer by remember { mutableStateOf<Answer?>(null) }

    fun lookup() {
        val id = kind.normalize(reference)
        if (id.isEmpty() || loading) return
        loading = true
        failure = null
        answer = null
        plus.scope.launch {
            if (kind == ServiceKind.Electricity) {
                when (val result = plus.repository.electricityBills(id)) {
                    is EnzonaResult.Ok -> {
                        answer = Answer.Bills(result.value.firstNotNullOfOrNull { it.owner }, result.value)
                        bill = result.value.indexOfFirst { it.charged != true }.takeIf { it >= 0 }?.toString()
                    }
                    is EnzonaResult.Failed -> failure = result.serviceFault?.description ?: result.reason.lookupMessage(id)
                }
            } else {
                val service = kind.invoiceService()
                when (val result = plus.repository.etecsaAccounts(service, id)) {
                    is EnzonaResult.Ok -> {
                        val account = result.value.accounts.firstOrNull { it.service == service && it.isValid == "true" }
                        if (account == null) failure = result.value.resultMessage ?: "ENZONA no encontró $id"
                        else answer = Answer.Etecsa(result.value.name, account)
                    }
                    is EnzonaResult.Failed -> failure = result.serviceFault?.description ?: result.reason.lookupMessage(id)
                }
            }
            loading = false
        }
    }
    LaunchedEffect(Unit) { if (dest.reference.isNotEmpty() && answer == null) lookup() }

    val index = source.coerceIn(0, (wallet.size - 1).coerceAtLeast(0))
    val card = wallet.getOrNull(index)?.card
    val currency = card?.currency ?: "CUP"
    val current = answer
    val lookupUi = when {
        loading -> ServiceLookupUi.Loading
        failure != null -> ServiceLookupUi.Failed(failure!!)
        current is Answer.Bills -> ServiceLookupUi.Bills(
            current.owner,
            current.bills.mapIndexedNotNull { i, b ->
                val value = b.amount ?: b.totalAmount ?: return@mapIndexedNotNull null
                BillUi(i.toString(), b.period ?: "Factura", Money(value, currency), paid = b.charged == true)
            },
            bill,
        )
        current is Answer.Etecsa && kind == ServiceKind.Landline -> current.account.toPay?.takeIf { it.signum() > 0 }
            ?.let { ServiceLookupUi.PhoneBill(current.owner, Money(it, currency)) }
            ?: ServiceLookupUi.Failed("No hay nada que pagar en este teléfono")
        current is Answer.Etecsa -> ServiceLookupUi.TopUp(
            current.owner,
            debt = listOfNotNull(current.account.currentDebt, current.account.unpaidDebt, current.account.currentUnpaidDebt)
                .firstOrNull { it.signum() > 0 }?.let { Money(it, currency) },
            min = current.account.minimumAmount?.let { Money(it, currency) },
            max = current.account.maximumAmount?.let { Money(it, currency) },
        )
        else -> ServiceLookupUi.None
    }
    val savedReference = saved.firstOrNull { it.kind == kind && it.value == kind.normalize(reference) }
    val topUp = lookupUi as? ServiceLookupUi.TopUp
    val typed = Amounts.parse(amount)
    val amountError = when {
        topUp == null || typed == null -> null
        topUp.min != null && typed < topUp.min.amount -> "Mínimo ${formatMoney(topUp.min.amount, topUp.min.currency)}"
        topUp.max != null && typed > topUp.max.amount -> "Máximo ${formatMoney(topUp.max.amount, topUp.max.currency)}"
        else -> null
    }
    val draft: EnzonaInvoicePaymentDraft? = card?.let { from ->
        runCatching {
            when (current) {
                is Answer.Bills -> current.bills.getOrNull(bill?.toIntOrNull() ?: -1)
                    ?.let { EnzonaInvoicePaymentDraft.electricity(it, from.id, from.currency) }
                is Answer.Etecsa -> when (kind) {
                    ServiceKind.Landline -> EnzonaInvoicePaymentDraft.fixedPhone(current.account, from.id, from.currency)
                    else -> typed?.takeIf { amountError == null }?.let { EnzonaInvoicePaymentDraft.nautaTopUp(current.account, it, from.id, from.currency) }
                }
                null -> null
            }
        }.getOrNull()
    }
    val actions = object : ServiceFormActions {
        override fun back() { plus.nav.back() }
        override fun reference(value: String) {
            reference = value
            answer = null
            failure = null
        }
        override fun pick(reference: SavedReferenceUi?) {
            reference(reference?.value?.let { kind.domain?.let(it::removeSuffix) ?: it }.orEmpty())
            if (reference != null) lookup()
        }
        override fun lookup() = lookup()
        override fun bill(id: String) { bill = id }
        override fun amount(value: String) { amount = value }
        override fun source(index: Int) { source = index }
        override fun pay() {
            val ready = draft ?: return
            val verb = if (kind == ServiceKind.Nauta || kind == ServiceKind.NautaHome) "Recargar" else "Pagar"
            payment.pay(ready, "$verb ${formatMoney(ready.amount, ready.currency)}", kind.label, "Guardar y pagar", name = null)
        }
        override fun editSaved() {
            editing = savedReference?.name.orEmpty()
            removing = false
        }
        override fun savedName(value: String) { editing = value.take(40) }
        override fun saveSaved() {
            val target = savedReference ?: return
            plus.savedMarks.rename(referenceKey(kind, target.value), editing.orEmpty())
            editing = null
        }
        override fun removeSaved() { removing = true }
        override fun confirmRemoveSaved() {
            val target = savedReference ?: return
            plus.savedMarks.remove(referenceKey(kind, target.value), System.currentTimeMillis())
            editing = null
            removing = false
            plus.nav.back()
        }
        override fun cancelRemoveSaved() { removing = false }
        override fun closeSaved() {
            editing = null
            removing = false
        }
    }
    ServiceFormScreen(
        ServiceFormUi(
            kind, reference, references = saved.filter { it.kind == kind }, selectedReference = savedReference?.value, lookup = lookupUi, amount = amount, amountError = amountError, currency = currency,
            cards = wallet, source = index, canPay = draft != null, paying = payment.busy,
            saved = savedReference != null,
            edit = editing?.takeIf { savedReference != null }?.let { SavedEditUi(it, removing) },
        ),
        actions,
    )
}
