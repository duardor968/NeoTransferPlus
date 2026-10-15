package dev.duardo.neotransfer.plus.app.routes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.duardo.neotransfer.plus.app.Dest
import dev.duardo.neotransfer.plus.app.PlusApp
import dev.duardo.neotransfer.plus.app.accountActivity
import dev.duardo.neotransfer.plus.app.movements
import dev.duardo.neotransfer.plus.app.toUi
import dev.duardo.neotransfer.plus.designsystem.format.formatAmount
import dev.duardo.neotransfer.plus.designsystem.format.formatMoney
import dev.duardo.neotransfer.plus.platform.shareText
import dev.duardo.neotransfer.plus.ui.activity.ActivityActions
import dev.duardo.neotransfer.plus.ui.activity.ActivityScreen
import dev.duardo.neotransfer.plus.ui.activity.OperationActions
import dev.duardo.neotransfer.plus.ui.activity.OperationDetailScreen
import dev.duardo.neotransfer.plus.ui.activity.detailLines
import dev.duardo.neotransfer.plus.ui.common.AppName
import dev.duardo.neotransfer.plus.ui.common.LocalClock
import dev.duardo.neotransfer.plus.ui.common.TimeLabels
import dev.duardo.neotransfer.plus.ui.common.label
import dev.duardo.neotransfer.plus.ui.common.statusText
import dev.duardo.neotransfer.plus.ui.model.ActivityContent
import dev.duardo.neotransfer.plus.ui.model.ActivityFilter
import dev.duardo.neotransfer.plus.ui.model.ActivitySource
import dev.duardo.neotransfer.plus.ui.model.ActivityUi
import dev.duardo.neotransfer.plus.ui.model.CounterpartUi
import dev.duardo.neotransfer.plus.ui.model.OperationUi
import dev.duardo.neotransfer.plus.ui.model.RefreshState
import java.util.Locale

@Composable
fun ActivityRoute(plus: PlusApp) {
    val query by plus.activity.collectAsState()
    val cards = rememberWallet(plus)
    val wallet by plus.wallet.collectAsState()
    val transactions by plus.transactions.collectAsState()
    val journal by plus.journal.collectAsState()
    val account by plus.account.collectAsState()
    val contacts by plus.contactList.collectAsState()
    val refresh by plus.refreshState.collectAsState()
    val refreshingCards by plus.refreshingCards.collectAsState()
    val olderFrom by plus.olderFrom.collectAsState()
    val loadingOlder by plus.loadingOlder.collectAsState()
    val cardUis = cards.map { it.card }
    val selectedCard = query.card?.takeIf { id -> cardUis.any { it.id == id } } ?: cardUis.firstOrNull()?.id

    val all = remember(transactions, journal, account, cardUis, contacts) {
        transactions?.let { accountActivity(it, journal, account?.username, cardUis, contacts.orEmpty(), plus.zone) }
    }
    val movements = remember(wallet, selectedCard) {
        wallet?.firstOrNull { it.card.fundingSourceUuid == selectedCard }?.movements(plus.zone)
    }
    val content = when (query.source) {
        ActivitySource.Account -> when {
            all == null -> ActivityContent.Loading
            all.isEmpty() -> if (refresh == RefreshState.Refreshing) ActivityContent.Loading else ActivityContent.Empty
            else -> all.filter { it.matches(query.query, query.filter) }.let { if (it.isEmpty()) ActivityContent.NoResults else ActivityContent.Items(it) }
        }
        ActivitySource.Cards -> when {
            wallet == null -> ActivityContent.Loading
            movements.isNullOrEmpty() -> if (selectedCard in refreshingCards) ActivityContent.Loading else ActivityContent.Empty
            else -> ActivityContent.Items(movements)
        }
    }
    val refreshing = if (query.source == ActivitySource.Cards) selectedCard in refreshingCards else refresh == RefreshState.Refreshing
    val actions = remember(plus) {
        object : ActivityActions {
            override fun source(value: ActivitySource) = plus.activity.value.let { plus.activity.value = it.copy(source = value) }
            override fun query(value: String) = plus.activity.value.let { plus.activity.value = it.copy(query = value) }
            override fun filter(value: ActivityFilter) = plus.activity.value.let { plus.activity.value = it.copy(filter = value) }
            override fun card(cardId: String?) = plus.activity.value.let { plus.activity.value = it.copy(card = cardId) }
            override fun open(id: String) = plus.nav.push(Dest.Operation(id))
            override fun refresh() {
                val current = plus.activity.value
                val card = current.card ?: plus.wallet.value?.firstOrNull()?.card?.fundingSourceUuid
                if (current.source == ActivitySource.Cards && card != null) plus.refreshBalance(card) else plus.refresh()
            }
            override fun loadOlder() = plus.loadOlder()
        }
    }
    ActivityScreen(
        ActivityUi(
            query.source, query.query, query.filter, cardUis, selectedCard, content,
            olderFrom = olderFrom, loadingOlder = loadingOlder,
            refresh = if (refreshing) RefreshState.Refreshing else RefreshState.Idle,
        ),
        actions,
    )
}

/** Search over what a person reads in the row: name, reference, card digits and amount. */
private fun OperationUi.matches(query: String, filter: ActivityFilter): Boolean {
    if (filter == ActivityFilter.In && !kind.incoming) return false
    if (filter == ActivityFilter.Out && kind.incoming) return false
    val needle = query.trim().lowercase(Locale.ROOT)
    if (needle.isEmpty()) return true
    val digits = needle.filter(Char::isDigit)
    val counterpartText = when (val c = counterpart) {
        is CounterpartUi.Card -> c.number
        is CounterpartUi.CardEnding -> c.last4
        is CounterpartUi.Account -> c.username
        is CounterpartUi.Reference -> c.value
        null -> ""
    }
    val words = listOfNotNull(title, description, counterpartText, kind.label).joinToString(" ").lowercase(Locale.ROOT)
    val amountText = amount?.let { formatAmount(it.amount) }.orEmpty().filter { it.isDigit() || it == '.' }
    return needle in words || (digits.isNotEmpty() && (digits in counterpartText || amountText.startsWith(needle.filter { it.isDigit() || it == '.' })))
}

/** One movement by its key, from whichever record holds it; null until loaded or once it is gone. */
@Composable
fun rememberOperation(plus: PlusApp, key: String): OperationUi? {
    val cards = rememberWallet(plus).map { it.card }
    val wallet by plus.wallet.collectAsState()
    val transactions by plus.transactions.collectAsState()
    val journal by plus.journal.collectAsState()
    val account by plus.account.collectAsState()
    val contacts by plus.contactList.collectAsState()
    val notices by plus.notices.collectAsState()
    val notSent by plus.notSent.collectAsState()
    val titles by plus.titles.collectAsState()
    return remember(key, cards, wallet, transactions, journal, account, contacts, notices, notSent, titles) {
        val (kind, rest) = key.substringBefore(':') to key.substringAfter(':')
        when (kind) {
            "tx" -> transactions?.firstOrNull { it.transactionUuid == rest }?.toUi(account?.username, cards, plus.zone)
            "j" -> journal.firstOrNull { it.intent.id == rest }?.let { entry ->
                val history = entry.remoteId?.let { id -> transactions?.firstOrNull { it.transactionUuid == id } }
                history?.toUi(account?.username, cards, plus.zone)
                    ?: entry.toUi(cards, contacts.orEmpty(), notices[rest], titles[rest], notSent[rest])
            }
            "m" -> {
                val card = rest.substringBeforeLast(':')
                val index = rest.substringAfterLast(':').toIntOrNull()
                wallet?.firstOrNull { it.card.fundingSourceUuid == card }?.movements(plus.zone)?.getOrNull(index ?: -1)
            }
            else -> null
        }
    }
}

@Composable
fun OperationRoute(plus: PlusApp, key: String) {
    val context = LocalContext.current
    val operation = rememberOperation(plus, key)
    val refresh by plus.refreshState.collectAsState()
    val transactions by plus.transactions.collectAsState()
    val clock = LocalClock.current
    // Once the records are loaded, a movement that is not there anymore (signed out, cleared) closes its receipt.
    LaunchedEffect(operation == null, transactions != null) {
        if (operation == null && transactions != null) plus.nav.back()
    }
    if (operation == null) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }
    val actions = remember(plus, operation) {
        object : OperationActions {
            override fun back() { plus.nav.back() }
            override fun share() = context.shareText(receiptText(operation, TimeLabels(clock)), "Compartir comprobante")
            override fun refresh() = plus.refresh()
        }
    }
    OperationDetailScreen(operation, actions, refreshing = refresh == RefreshState.Refreshing)
}

/** The receipt as plain text: what, who, how much, state and the same lines as on screen. */
fun receiptText(operation: OperationUi, labels: TimeLabels): String = buildString {
    appendLine("${operation.kind.label} · $AppName")
    appendLine(operation.title)
    operation.amount?.let { appendLine(formatMoney(it.amount, it.currency)) }
    operation.statusText.takeIf(String::isNotEmpty)?.let(::appendLine)
    detailLines(operation, labels).forEach { line ->
        appendLine("${line.label}: ${line.value}" + (line.detail?.let { " ($it)" } ?: ""))
    }
}.trimEnd()
