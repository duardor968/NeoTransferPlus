package dev.duardo.neotransfer.plus.ui.payment

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.plus.designsystem.R as Ds
import dev.duardo.neotransfer.plus.designsystem.component.NeoBottomSheet
import dev.duardo.neotransfer.plus.designsystem.component.NeoButton
import dev.duardo.neotransfer.plus.designsystem.component.NeoButtonStyle
import dev.duardo.neotransfer.plus.designsystem.component.NeoIconButton
import dev.duardo.neotransfer.plus.designsystem.component.NeoTextField
import dev.duardo.neotransfer.plus.designsystem.component.SecureWindow
import dev.duardo.neotransfer.plus.designsystem.format.formatMoney
import dev.duardo.neotransfer.plus.designsystem.theme.Neo
import dev.duardo.neotransfer.plus.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.plus.ui.common.BottomAction
import dev.duardo.neotransfer.plus.ui.common.ConfirmedMark
import dev.duardo.neotransfer.plus.ui.common.statusText
import dev.duardo.neotransfer.plus.ui.model.OperationKind
import dev.duardo.neotransfer.plus.ui.model.OperationStatus
import dev.duardo.neotransfer.plus.ui.model.PasswordSheetUi
import dev.duardo.neotransfer.plus.ui.model.PaymentResultUi

// ---- Result -------------------------------------------------------------------------------------------------

interface ResultActions {
    fun done()
    fun open()
    fun saveContact()
}

/**
 * After the single request. Sending turns into the confirmed check, the rejection, or "Sin confirmar" when ENZONA
 * does not prove the result in time: then the money may or may not have moved, and the activity tells.
 */
@Composable
fun PaymentResultScreen(state: PaymentResultUi, actions: ResultActions, modifier: Modifier = Modifier) {
    val operation = state.operation
    Scaffold(modifier, containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = NeoSpace.gutter),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Spacer(Modifier.height(NeoSpace.huge))
                when (operation.status) {
                    OperationStatus.Confirmed -> ConfirmedMark(128.dp)
                    OperationStatus.Sending -> SendingMark()
                    OperationStatus.Rejected -> Glyph(Ds.drawable.ic_close, Neo.colors.dangerContainer, Neo.colors.danger)
                    OperationStatus.NotSent -> Glyph(Ds.drawable.ic_close, Neo.colors.neutralContainer, Neo.colors.neutral)
                    else -> Glyph(Ds.drawable.ic_sync_problem, Neo.colors.warningContainer, Neo.colors.warning)
                }
                Spacer(Modifier.height(NeoSpace.xxl))
                Text(operation.statusText, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(NeoSpace.s))
                operation.amount?.let { money ->
                    val to = if (operation.kind == OperationKind.CardTransfer || operation.kind == OperationKind.AccountTransfer) " a " else " · "
                    Text("${formatMoney(money.amount, money.currency)}$to${operation.title}", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                }
                val note = when (operation.status) {
                    OperationStatus.Rejected, OperationStatus.Unconfirmed, OperationStatus.NotSent -> operation.reason
                    OperationStatus.Confirmed -> operation.transactionId?.let { "Transacción $it" }
                    else -> null
                }
                note?.let {
                    Text(
                        it,
                        Modifier.padding(top = NeoSpace.s),
                        style = MaterialTheme.typography.bodyMedium,
                        color = when (operation.status) {
                            OperationStatus.Rejected -> Neo.colors.danger
                            OperationStatus.Unconfirmed -> Neo.colors.warning
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(NeoSpace.huge))
            }
            if (operation.status != OperationStatus.Sending) BottomAction {
                if (state.canSaveContact) NeoButton("Guardar contacto", actions::saveContact, style = NeoButtonStyle.Tonal, icon = Ds.drawable.ic_person_add)
                else if (operation.status != OperationStatus.NotSent) NeoButton("Ver operación", actions::open, style = NeoButtonStyle.Tonal)
                NeoButton("Volver al inicio", actions::done)
            }
        }
    }
}

@Composable
private fun Glyph(icon: Int, container: androidx.compose.ui.graphics.Color, tint: androidx.compose.ui.graphics.Color) {
    Box(Modifier.size(112.dp).background(container, CircleShape), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), null, Modifier.size(48.dp), tint = tint)
    }
}

/** The money is on its way: the arrow inside a slowly turning arc. */
@Composable
private fun SendingMark() {
    val transition = rememberInfiniteTransition(label = "sending")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "arc")
    val arc = Neo.colors.accent
    val track = Neo.colors.accentContainer
    Box(
        Modifier.size(128.dp).drawBehind {
            val stroke = 4.dp.toPx()
            val inset = stroke / 2
            val diameter = size.minDimension - stroke
            drawArc(track, 0f, 360f, false, Offset(inset, inset), Size(diameter, diameter), style = Stroke(stroke))
            drawArc(arc, angle - 90f, 70f, false, Offset(inset, inset), Size(diameter, diameter), style = Stroke(stroke, cap = StrokeCap.Round))
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(96.dp).background(track, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(Ds.drawable.ic_arrow_outward), null, Modifier.size(44.dp), tint = Neo.colors.onAccentContainer)
        }
    }
}

// ---- Payment password ---------------------------------------------------------------------------------------

interface PasswordSheetActions {
    fun value(value: String)
    fun submit()
    fun dismiss()
}

/**
 * The ENZONA payment password, asked once: the fingerprint keeps it on this phone and unlocks it for every
 * payment after this one. Secret: no screenshots while open.
 */
@Composable
fun PasswordSheet(state: PasswordSheetUi, actions: PasswordSheetActions) {
    SecureWindow()
    NeoBottomSheet(onDismissRequest = actions::dismiss, title = "Contraseña de pago") {
        Column(Modifier.padding(horizontal = NeoSpace.xl), verticalArrangement = Arrangement.spacedBy(NeoSpace.l)) {
            PasswordField(state.value, actions::value, "Contraseña de pago de ENZONA", state.error)
            NeoButton(state.action, actions::submit, icon = Ds.drawable.ic_fingerprint, enabled = state.value.isNotEmpty())
        }
    }
}

@Composable
fun PasswordField(value: String, onChange: (String) -> Unit, label: String, error: String?) {
    var visible by remember { mutableStateOf(false) }
    NeoTextField(
        value, onChange, label,
        keyboardType = KeyboardType.Password,
        imeAction = ImeAction.Done,
        error = error,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailing = {
            NeoIconButton(
                if (visible) Ds.drawable.ic_visibility_off else Ds.drawable.ic_visibility,
                if (visible) "Ocultar contraseña" else "Mostrar contraseña",
                { visible = !visible },
            )
        },
    )
}
