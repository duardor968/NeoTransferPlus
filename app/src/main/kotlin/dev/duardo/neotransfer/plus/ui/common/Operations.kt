package dev.duardo.neotransfer.plus.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.plus.designsystem.R as Ds
import dev.duardo.neotransfer.plus.designsystem.component.AvatarBadge
import dev.duardo.neotransfer.plus.designsystem.component.IconAvatar
import dev.duardo.neotransfer.plus.designsystem.component.MoneySign
import dev.duardo.neotransfer.plus.designsystem.component.MoneyText
import dev.duardo.neotransfer.plus.designsystem.component.NameAvatar
import dev.duardo.neotransfer.plus.designsystem.component.NeoRow
import dev.duardo.neotransfer.plus.designsystem.component.SeparatedText
import dev.duardo.neotransfer.plus.designsystem.component.StatusChip
import dev.duardo.neotransfer.plus.designsystem.component.StatusTone
import dev.duardo.neotransfer.plus.designsystem.component.content
import dev.duardo.neotransfer.plus.designsystem.theme.Neo
import dev.duardo.neotransfer.plus.designsystem.theme.NeoAccents
import dev.duardo.neotransfer.plus.ui.model.OperationKind
import dev.duardo.neotransfer.plus.ui.model.OperationOrigin
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
        OperationStatus.NotDone -> "No realizada"
        OperationStatus.Other -> statusLabel.orEmpty()
    }

val OperationStatus.tone: StatusTone
    get() = when (this) {
        OperationStatus.Sending, OperationStatus.Pending -> StatusTone.Pending
        OperationStatus.Confirmed -> StatusTone.Positive
        OperationStatus.Rejected -> StatusTone.Danger
        OperationStatus.Unconfirmed -> StatusTone.Warning
        OperationStatus.NotSent, OperationStatus.NotDone, OperationStatus.Other -> StatusTone.Neutral
    }

@get:DrawableRes
val OperationStatus.icon: Int
    get() = when (this) {
        OperationStatus.Sending, OperationStatus.Pending -> Ds.drawable.ic_schedule
        OperationStatus.Confirmed -> Ds.drawable.ic_check_circle
        OperationStatus.Rejected -> Ds.drawable.ic_cancel
        OperationStatus.Unconfirmed -> Ds.drawable.ic_sync_problem
        OperationStatus.NotSent, OperationStatus.NotDone -> Ds.drawable.ic_close
        OperationStatus.Other -> Ds.drawable.ic_info
    }

/** Money that certainly did not move, or that the person says did not, is shown struck through. */
val OperationStatus.moneyMoved: Boolean get() = this != OperationStatus.Rejected && this != OperationStatus.NotSent && this != OperationStatus.NotDone

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
        OperationKind.Water -> "Agua"
        OperationKind.MobileTopUp -> "Recarga móvil"
        OperationKind.CubacelTransfer -> "Saldo Cubacel transferido"
        OperationKind.Other -> "Operación"
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
        OperationKind.Water -> Ds.drawable.ic_water_drop
        OperationKind.MobileTopUp -> Ds.drawable.ic_smartphone
        OperationKind.CubacelTransfer -> Ds.drawable.ic_swap_horiz
        OperationKind.Other -> Ds.drawable.ic_receipt_long
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

/**
 * The title names a person. A card's bank record names no one, even for a credit, unless NeoTransfer told who it was.
 */
val OperationUi.namesPerson: Boolean
    get() = kind.person && (channel == null || origin == OperationOrigin.NeoTransfer) && title.firstOrNull()?.isLetter() == true

/**
 * People get initials with a direction badge; services, merchants and card lines get their glyph. The [origin] dot
 * sits at the bottom left, clear of the direction badge.
 */
@Composable
fun OperationAvatar(operation: OperationUi) {
    val person = operation.namesPerson
    Box {
        if (person) {
            val badge = if (operation.kind.incoming) {
                AvatarBadge(Ds.drawable.ic_south_west, Neo.colors.accent, Neo.colors.onAccent)
            } else {
                AvatarBadge(Ds.drawable.ic_arrow_outward, MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurface)
            }
            NameAvatar(operation.title, badge = badge)
        } else {
            IconAvatar(operation.kind.icon)
        }
        operation.origin?.let { origin ->
            OriginDot(origin, Modifier.align(Alignment.BottomStart).offset((-2).dp, 2.dp))
        }
    }
}

/** The app's brand colour: royal blue for this app, NeoTransfer's mint for NeoTransfer. */
@Composable
private fun OriginDot(origin: OperationOrigin, modifier: Modifier) {
    val accent = if (origin == OperationOrigin.Enzona) NeoAccents.RoyalBlue else NeoAccents.Mint
    Box(
        modifier
            .size(16.dp)
            .border(2.dp, Neo.colors.group, CircleShape)
            .padding(2.dp)
            .background(if (Neo.colors.isDark) accent.onDark else accent.onLight, CircleShape),
    )
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
fun OperationRow(operation: OperationUi, timeLabel: String, onClick: () -> Unit, modifier: Modifier = Modifier, title: String = operation.title) {
    NeoRow(
        title = title,
        modifier = modifier,
        leading = { OperationAvatar(operation) },
        supportingContent = {
            val status = operation.status
            val text = if (status == OperationStatus.Confirmed) timeLabel else listOf(timeLabel, operation.statusText).filter(String::isNotEmpty).joinToString(" · ")
            SeparatedText(
                text,
                MaterialTheme.typography.bodyMedium,
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
