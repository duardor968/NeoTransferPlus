package dev.duardo.neotransfer.plus.ui.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.plus.designsystem.R as Ds
import dev.duardo.neotransfer.plus.designsystem.component.IconAvatar
import dev.duardo.neotransfer.plus.designsystem.component.MoneySign
import dev.duardo.neotransfer.plus.designsystem.component.MoneyText
import dev.duardo.neotransfer.plus.designsystem.component.NameAvatar
import dev.duardo.neotransfer.plus.designsystem.component.NeoButton
import dev.duardo.neotransfer.plus.designsystem.component.NeoButtonStyle
import dev.duardo.neotransfer.plus.designsystem.component.NeoTopBar
import dev.duardo.neotransfer.plus.designsystem.component.ReceiptTicket
import dev.duardo.neotransfer.plus.designsystem.component.SummaryLine
import dev.duardo.neotransfer.plus.designsystem.component.SummaryLines
import dev.duardo.neotransfer.plus.designsystem.component.content
import dev.duardo.neotransfer.plus.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.plus.ui.common.ConfirmedMark
import dev.duardo.neotransfer.plus.ui.common.OperationStatusChip
import dev.duardo.neotransfer.plus.ui.common.TimeLabels
import dev.duardo.neotransfer.plus.ui.common.icon
import dev.duardo.neotransfer.plus.ui.common.label
import dev.duardo.neotransfer.plus.ui.common.moneyMoved
import dev.duardo.neotransfer.plus.ui.common.numberLine
import dev.duardo.neotransfer.plus.ui.common.timeLabels
import dev.duardo.neotransfer.plus.ui.common.tone
import dev.duardo.neotransfer.plus.ui.model.CounterpartUi
import dev.duardo.neotransfer.plus.ui.model.OperationKind
import dev.duardo.neotransfer.plus.ui.model.OperationStatus
import dev.duardo.neotransfer.plus.ui.model.OperationUi
import dev.duardo.neotransfer.plus.designsystem.format.formatCardNumber

interface OperationActions {
    fun back()
    fun share()
    fun refresh()
}

/**
 * Receipt of one movement. The textured check appears only once ENZONA confirmed it; otherwise the state chip and,
 * when it needs attention, what happened in plain words.
 */
@Composable
fun OperationDetailScreen(operation: OperationUi, actions: OperationActions, modifier: Modifier = Modifier, refreshing: Boolean = false) {
    Scaffold(
        modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { NeoTopBar(operation.kind.label, onBack = actions::back) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = NeoSpace.gutter).padding(top = NeoSpace.s, bottom = NeoSpace.xxl),
            ) {
                ReceiptTicket(
                    header = { TicketHeader(operation) },
                    body = { SummaryLines(detailLines(operation, timeLabels), Modifier.padding(vertical = NeoSpace.s)) },
                )
            }
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = NeoSpace.gutter, vertical = NeoSpace.l),
                verticalArrangement = Arrangement.spacedBy(NeoSpace.s),
            ) {
                when (operation.status) {
                    OperationStatus.Unconfirmed -> NeoButton("Actualizar actividad", actions::refresh, style = NeoButtonStyle.Tonal, icon = Ds.drawable.ic_refresh, loading = refreshing)
                    OperationStatus.Sending -> Unit
                    else -> NeoButton("Compartir comprobante", actions::share, icon = Ds.drawable.ic_share)
                }
            }
        }
    }
}

@Composable
private fun TicketHeader(operation: OperationUi) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = NeoSpace.xl, vertical = NeoSpace.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NeoSpace.m),
    ) {
        val person = operation.kind in setOf(OperationKind.CardTransfer, OperationKind.AccountTransfer, OperationKind.Incoming)
        when {
            operation.status == OperationStatus.Confirmed && operation.kind != OperationKind.CardMovement -> ConfirmedMark(80.dp)
            person && operation.title.firstOrNull()?.isLetter() == true -> NameAvatar(operation.title, size = 64.dp)
            else -> IconAvatar(operation.kind.icon, size = 64.dp)
        }
        Text(operation.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        operation.amount?.let { money ->
            MoneyText(
                money.amount, money.currency,
                style = MaterialTheme.typography.displaySmall.copy(textDecoration = if (operation.status.moneyMoved) null else TextDecoration.LineThrough),
                sign = if (operation.kind.incoming) MoneySign.Plus else MoneySign.None,
                hero = true,
            )
        }
        if (operation.kind != OperationKind.CardMovement) OperationStatusChip(operation)
        operation.reason?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, color = operation.status.tone.content(), textAlign = TextAlign.Center)
        }
    }
}

/** Lines of the receipt, also what is shared. Values already shown in the header are not repeated. */
fun detailLines(operation: OperationUi, labels: TimeLabels): List<SummaryLine> {
    val counterpart = when (val c = operation.counterpart) {
        is CounterpartUi.Card -> SummaryLine(if (operation.kind.incoming) "Desde la tarjeta" else "Tarjeta destino", formatCardNumber(c.number), c.bank)
        is CounterpartUi.CardEnding -> SummaryLine(if (operation.kind.incoming) "Desde la tarjeta" else "Tarjeta destino", "•••• ${c.last4}", c.bank)
        is CounterpartUi.Account -> SummaryLine(if (operation.kind.incoming) "Desde la cuenta" else "Cuenta ENZONA", c.username)
        is CounterpartUi.Reference -> SummaryLine(referenceLabel(operation.kind), c.value)
        null -> null
    }?.takeIf { it.value != operation.title }
    return listOfNotNull(
        counterpart,
        operation.card?.let { SummaryLine(if (operation.kind.incoming) "A tu tarjeta" else "Desde", it.numberLine, it.bank) },
        operation.description?.takeIf { it != operation.title }?.let { SummaryLine("Concepto", it) },
        operation.channel?.let { SummaryLine("Canal", it) },
        SummaryLine("Fecha", operation.at?.let(labels::stamp) ?: operation.dateText ?: "Sin fecha"),
        operation.transactionId?.let { SummaryLine("Transacción", it) },
    )
}

private fun referenceLabel(kind: OperationKind): String = when (kind) {
    OperationKind.QrPayment -> "Comercio"
    OperationKind.Electricity -> "Código de cliente"
    OperationKind.Landline -> "Teléfono"
    OperationKind.Nauta, OperationKind.NautaHome -> "Cuenta"
    else -> "Referencia"
}
