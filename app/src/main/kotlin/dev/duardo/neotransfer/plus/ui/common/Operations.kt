package dev.duardo.neotransfer.plus.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import dev.duardo.neotransfer.plus.designsystem.R as Ds
import dev.duardo.neotransfer.plus.designsystem.component.AvatarBadge
import dev.duardo.neotransfer.plus.designsystem.component.IconAvatar
import dev.duardo.neotransfer.plus.designsystem.component.MoneySign
import dev.duardo.neotransfer.plus.designsystem.component.MoneyText
import dev.duardo.neotransfer.plus.designsystem.component.NameAvatar
import dev.duardo.neotransfer.plus.designsystem.component.NeoRow
import dev.duardo.neotransfer.plus.designsystem.component.StatusChip
import dev.duardo.neotransfer.plus.designsystem.component.StatusTone
import dev.duardo.neotransfer.plus.designsystem.component.content
import dev.duardo.neotransfer.plus.designsystem.theme.Neo
import dev.duardo.neotransfer.plus.ui.model.OperationKind
import dev.duardo.neotransfer.plus.ui.model.OperationStatus
import dev.duardo.neotransfer.plus.ui.model.OperationUi
import dev.duardo.neotransfer.plus.ui.model.ServiceKind

val OperationUi.statusText: String
    get() = when (status) {
        OperationStatus.Sending -> "Enviando"
        OperationStatus.Confirmed -> "Confirmada"
        OperationStatus.Pending -> "Pendiente"
        OperationStatus.Rejected -> "Rechazada"
        OperationStatus.Unconfirmed -> "Sin confirmar"
        OperationStatus.NotSent -> "No enviada"
        OperationStatus.Other -> statusLabel.orEmpty()
    }

val OperationStatus.tone: StatusTone
    get() = when (this) {
        OperationStatus.Sending, OperationStatus.Pending -> StatusTone.Pending
        OperationStatus.Confirmed -> StatusTone.Positive
        OperationStatus.Rejected -> StatusTone.Danger
        OperationStatus.Unconfirmed -> StatusTone.Warning
        OperationStatus.NotSent, OperationStatus.Other -> StatusTone.Neutral
    }

@get:DrawableRes
val OperationStatus.icon: Int
    get() = when (this) {
        OperationStatus.Sending, OperationStatus.Pending -> Ds.drawable.ic_schedule
        OperationStatus.Confirmed -> Ds.drawable.ic_check_circle
        OperationStatus.Rejected -> Ds.drawable.ic_cancel
        OperationStatus.Unconfirmed -> Ds.drawable.ic_sync_problem
        OperationStatus.NotSent -> Ds.drawable.ic_close
        OperationStatus.Other -> Ds.drawable.ic_info
    }

/** Money that certainly did not move is shown struck through. */
val OperationStatus.moneyMoved: Boolean get() = this != OperationStatus.Rejected && this != OperationStatus.NotSent

/** Nothing when ENZONA gave no state word at all. */
@Composable
fun OperationStatusChip(operation: OperationUi, modifier: Modifier = Modifier) {
    if (operation.statusText.isNotEmpty()) StatusChip(operation.statusText, operation.status.tone, operation.status.icon, modifier)
}

val OperationKind.label: String
    get() = when (this) {
        OperationKind.CardTransfer -> "Transferencia"
        OperationKind.AccountTransfer -> "Transferencia a cuenta"
        OperationKind.Incoming -> "Transferencia recibida"
        OperationKind.QrPayment -> "Pago con QR"
        OperationKind.Electricity -> "Electricidad"
        OperationKind.Landline -> "Teléfono fijo"
        OperationKind.Nauta -> "Nauta"
        OperationKind.NautaHome -> "Nauta Hogar"
        OperationKind.Service -> "Pago de servicio"
        OperationKind.CardMovement -> "Movimiento de tarjeta"
    }

@get:DrawableRes
val OperationKind.icon: Int
    get() = when (this) {
        OperationKind.CardTransfer, OperationKind.AccountTransfer -> Ds.drawable.ic_arrow_outward
        OperationKind.Incoming -> Ds.drawable.ic_south_west
        OperationKind.QrPayment -> Ds.drawable.ic_qr_code
        OperationKind.Electricity -> Ds.drawable.ic_bolt
        OperationKind.Landline -> Ds.drawable.ic_deskphone
        OperationKind.Nauta -> Ds.drawable.ic_wifi
        OperationKind.NautaHome -> Ds.drawable.ic_router
        OperationKind.Service -> Ds.drawable.ic_receipt_long
        OperationKind.CardMovement -> Ds.drawable.ic_credit_card
    }

@get:DrawableRes
val ServiceKind.icon: Int
    get() = when (this) {
        ServiceKind.Electricity -> Ds.drawable.ic_bolt
        ServiceKind.Landline -> Ds.drawable.ic_deskphone
        ServiceKind.Nauta -> Ds.drawable.ic_wifi
        ServiceKind.NautaHome -> Ds.drawable.ic_router
    }

private val OperationKind.person: Boolean
    get() = this == OperationKind.CardTransfer || this == OperationKind.AccountTransfer || this == OperationKind.Incoming

/** People get initials with a direction badge; services, merchants and card lines get their glyph. */
@Composable
fun OperationAvatar(operation: OperationUi) {
    if (operation.kind.person && operation.title.firstOrNull()?.isLetter() == true) {
        val badge = if (operation.kind.incoming) {
            AvatarBadge(Ds.drawable.ic_south_west, Neo.colors.accent, Neo.colors.onAccent)
        } else {
            AvatarBadge(Ds.drawable.ic_arrow_outward, MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurface)
        }
        NameAvatar(operation.title, badge = badge)
    } else {
        IconAvatar(operation.kind.icon)
    }
}

/** Amount as listed: incoming in positive ink with a plus; money that never moved, struck through. */
@Composable
fun OperationAmount(operation: OperationUi, modifier: Modifier = Modifier) {
    val money = operation.amount ?: return
    val moved = operation.status.moneyMoved
    val incoming = operation.kind.incoming
    MoneyText(
        money.amount,
        money.currency,
        modifier,
        style = MaterialTheme.typography.titleMedium.copy(textDecoration = if (moved) null else TextDecoration.LineThrough),
        color = when {
            !moved -> MaterialTheme.colorScheme.onSurfaceVariant
            incoming && operation.status == OperationStatus.Confirmed -> Neo.colors.positive
            else -> MaterialTheme.colorScheme.onSurface
        },
        sign = if (incoming) MoneySign.Plus else MoneySign.None,
    )
}

/**
 * Row of a movement. [timeLabel] is chosen by the list (clock time inside a day group, moment elsewhere); the state
 * is added unless the operation is confirmed.
 */
@Composable
fun OperationRow(operation: OperationUi, timeLabel: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    NeoRow(
        title = operation.title,
        modifier = modifier,
        leading = { OperationAvatar(operation) },
        supportingContent = {
            val status = operation.status
            val text = if (status == OperationStatus.Confirmed) timeLabel else listOf(timeLabel, operation.statusText).filter(String::isNotEmpty).joinToString(" · ")
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = when (status.tone) {
                    StatusTone.Danger, StatusTone.Warning -> status.tone.content()
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
        trailing = { OperationAmount(operation) },
        onClick = onClick,
        trailingBelowWhenLarge = true,
    )
}
