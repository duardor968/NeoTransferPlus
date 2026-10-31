package dev.duardo.neotransfer.plus.app.routes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.duardo.neotransfer.plus.app.ActivityQuery
import dev.duardo.neotransfer.plus.app.Dest
import dev.duardo.neotransfer.plus.app.LocalAuthorizer
import dev.duardo.neotransfer.plus.app.PlusApp
import dev.duardo.neotransfer.plus.app.accountActivity
import dev.duardo.neotransfer.plus.app.inProgress
import dev.duardo.neotransfer.plus.app.toUi
import dev.duardo.neotransfer.plus.core.payment.EnzonaIntentState
import dev.duardo.neotransfer.plus.data.EnzonaFailure
import dev.duardo.neotransfer.plus.designsystem.format.formatMoney
import dev.duardo.neotransfer.plus.platform.copyText
import dev.duardo.neotransfer.plus.ui.home.CardSheetActions
import dev.duardo.neotransfer.plus.ui.home.HomeActions
import dev.duardo.neotransfer.plus.ui.home.HomeScreen
import dev.duardo.neotransfer.plus.ui.model.ActivitySource
import dev.duardo.neotransfer.plus.ui.model.CardSheetUi
import dev.duardo.neotransfer.plus.ui.model.HomeUi
import dev.duardo.neotransfer.plus.ui.model.RefreshState
import dev.duardo.neotransfer.plus.ui.model.SavedReferenceUi
import dev.duardo.neotransfer.plus.ui.model.ServiceKind
import dev.duardo.neotransfer.plus.ui.shell.Tab
import kotlinx.coroutines.launch

@Composable
fun HomeRoute(plus: PlusApp) {
    val context = LocalContext.current
    val cards = rememberWallet(plus)
    val wallet by plus.wallet.collectAsState()
    val refresh by plus.refreshState.collectAsState()
    val transactions by plus.transactions.collectAsState()
    val journal by plus.journal.collectAsState()
    val account by plus.account.collectAsState()
    val contacts by plus.contactList.collectAsState()
    val selected by plus.selectedCard.collectAsState()
    val saved by plus.saved.collectAsState()
    val authorizer = LocalAuthorizer.current
    val nav = plus.nav

    val cardUis = cards.map { it.card }
    val history = transactions
    val activity = remember(history, journal, cardUis, account, contacts) {
        history?.let { accountActivity(it, journal.orEmpty(), account?.username, cardUis, contacts.orEmpty(), plus.zone, withPending = false) }
    }
    val recent = when {
        wallet == null || history == null -> null
        history.isEmpty() && refresh == RefreshState.Refreshing -> null
        else -> activity
    }
    val pending = remember(journal, history, cardUis, contacts) {
        inProgress(journal.orEmpty(), history.orEmpty().map { it.transactionUuid }.toSet(), cardUis, contacts.orEmpty(), System.currentTimeMillis())
    }
    var recharging by rememberSaveable { mutableStateOf(false) }

    var sheetCard by rememberSaveable { mutableStateOf<String?>(null) }
    var input by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val sheet = cardUis.firstOrNull { it.id == sheetCard }?.let { CardSheetUi(it, input, error, saving) }

    val actions = remember(plus, authorizer) {
        object : HomeActions {
            /** Paying needs a card of the account: without one, adding it comes first. */
            private val noCards get() = plus.wallet.value?.isEmpty() == true

            override fun refresh() = plus.refresh()
            override fun selectCard(index: Int) { plus.selectedCard.value = index }
            override fun refreshBalance(cardId: String) = plus.refreshBalance(cardId)
            override fun openCard(cardId: String) {
                sheetCard = cardId
                input = ""
                error = null
            }
            override fun addCard() = nav.push(Dest.AddCard)
            override fun transfer() = if (noCards) addCard() else nav.push(Dest.Recipient)
            override fun pay() = if (noCards) addCard() else nav.push(Dest.Pay())
            override fun receive() = nav.push(Dest.Receive)
            override fun recharge() {
                if (noCards) addCard() else recharging = true
            }
            override fun rechargeTo(reference: SavedReferenceUi) {
                recharging = false
                nav.push(Dest.Service(reference.kind, reference.value))
            }
            override fun rechargeNew(kind: ServiceKind) {
                recharging = false
                nav.push(Dest.Service(kind))
            }
            override fun closeRecharge() { recharging = false }
            override fun openOperation(id: String) = nav.push(Dest.Operation(id))
            override fun retry(id: String) {
                val entry = plus.journal.value?.firstOrNull { "j:${it.intent.id}" == id && it.state == EnzonaIntentState.NOT_DELIVERED } ?: return
                val operation = entry.toUi(plus.wallet.value.orEmpty().map { it.toUi() }, plus.contactList.value.orEmpty())
                val money = operation.amount?.let { formatMoney(it.amount, it.currency) }.orEmpty()
                // Its one dispatch never reached ENZONA: a new intent with the same values, a new approval, its own result.
                plus.scope.launch {
                    val again = try { plus.prepareAgain(entry.intent) } catch (_: Exception) {
                        plus.say("No se pudo preparar el pago.")
                        return@launch
                    }
                    authorizer.authorize("Enviar $money", operation.title, "Guardar y enviar", onPassword = { password, sealed ->
                        plus.submit(again, password, sealed)
                        nav.push(Dest.Result(again.id))
                    }, onProblem = plus::say)
                }
            }
            override fun allActivity() {
                plus.activity.value = plus.activity.value.copy(source = ActivitySource.Account)
                nav.tab = Tab.Activity
            }
            override fun settings() = nav.push(Dest.Settings)
        }
    }
    val sheetActions = object : CardSheetActions {
        override fun number(value: String) {
            input = value.filter(Char::isDigit).take(16)
            error = null
        }
        override fun save() {
            val card = sheet?.card ?: return
            saving = true
            plus.scope.launch {
                val failure = plus.repository.saveCardLink(card.id, input, null)
                saving = false
                when (failure) {
                    null -> sheetCard = null
                    EnzonaFailure.INVALID_INPUT -> error = card.last4?.let { "Este número no termina en $it" } ?: "Número no válido"
                    else -> error = failure.message()
                }
            }
        }
        override fun copy() {
            sheet?.card?.number?.let { context.copyText("Número de tarjeta", it) }
        }
        override fun movements() {
            val id = sheetCard ?: return
            sheetCard = null
            plus.activity.value = ActivityQuery(source = ActivitySource.Cards, card = id)
            nav.tab = Tab.Activity
        }
        override fun settings() {
            sheetCard = null
            nav.push(Dest.Settings)
        }
        override fun close() { sheetCard = null }
    }

    HomeScreen(
        HomeUi(
            cards, selected.coerceIn(0, (cards.size - 1).coerceAtLeast(0)), pending, recent, refresh,
            recharge = if (recharging) saved.filter { it.kind == ServiceKind.Nauta || it.kind == ServiceKind.NautaHome } else null,
        ),
        actions,
        sheet = sheet,
        sheetActions = sheetActions,
    )
}
