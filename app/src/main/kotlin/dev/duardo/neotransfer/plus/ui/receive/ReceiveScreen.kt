package dev.duardo.neotransfer.plus.ui.receive

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dev.duardo.neotransfer.plus.designsystem.R as Ds
import dev.duardo.neotransfer.plus.designsystem.component.EmptyState
import dev.duardo.neotransfer.plus.designsystem.component.MoneyText
import dev.duardo.neotransfer.plus.designsystem.component.NeoAmountField
import dev.duardo.neotransfer.plus.designsystem.component.NeoBottomSheet
import dev.duardo.neotransfer.plus.designsystem.component.NeoButton
import dev.duardo.neotransfer.plus.designsystem.component.NeoButtonStyle
import dev.duardo.neotransfer.plus.designsystem.component.NeoIconButton
import dev.duardo.neotransfer.plus.designsystem.component.NeoTopBar
import dev.duardo.neotransfer.plus.designsystem.component.SeparatedText
import dev.duardo.neotransfer.plus.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.plus.ui.common.BottomAction
import dev.duardo.neotransfer.plus.ui.common.BrandMark
import dev.duardo.neotransfer.plus.ui.model.ReceiveUi

interface ReceiveActions {
    fun back()
    fun requestAmount()
    fun amount(value: String)
    fun showAmount()
    fun closeAmount()
    fun clearAmount()
    /** Asks ENZONA again for the code it could not issue. */
    fun retry()
    fun share()
    fun copyAccount()
}

/** Cobrar: the account's QR at once, for the other person to scan and pay, with the amount already set if asked for. */
@Composable
fun ReceiveScreen(state: ReceiveUi, actions: ReceiveActions, modifier: Modifier = Modifier) {
    Scaffold(
        modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            NeoTopBar("Cobrar", onBack = actions::back, actions = {
                if (state.username != null) NeoIconButton(Ds.drawable.ic_content_copy, "Copiar cuenta", actions::copyAccount)
            })
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(NeoSpace.xl))
                state.amount?.let { money ->
                    MoneyText(money.amount, money.currency, style = MaterialTheme.typography.displaySmall, hero = true)
                    Spacer(Modifier.height(NeoSpace.l))
                }
                if (state.error != null && state.payload == null) {
                    EmptyState(Ds.drawable.ic_sync_problem, state.error, action = "Reintentar", onAction = actions::retry)
                } else {
                    // A QR is read dark on light: the plate stays white in both themes.
                    Box(
                        Modifier
                            .padding(horizontal = NeoSpace.huge)
                            .widthIn(max = 320.dp)
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .background(Color.White, RoundedCornerShape(28.dp))
                            .padding(NeoSpace.xl),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (state.payload != null) {
                            QrCode(state.payload, Modifier.fillMaxSize().semantics { contentDescription = "Código QR para cobrar" })
                            Box(Modifier.size(64.dp).background(Color.White, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                                BrandMark(52.dp)
                            }
                        } else {
                            CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp, color = Color(0xFF5A616A))
                        }
                    }
                }
                Spacer(Modifier.height(NeoSpace.xl))
                state.owner?.let { Text(it, Modifier.padding(horizontal = NeoSpace.gutter), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center) }
                state.username?.let {
                    SeparatedText(
                        "Cuenta ENZONA · $it", MaterialTheme.typography.bodyMedium, Modifier.padding(horizontal = NeoSpace.gutter),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(NeoSpace.xl))
            }
            BottomAction {
                Row(horizontalArrangement = Arrangement.spacedBy(NeoSpace.s)) {
                    if (state.amount == null) NeoButton("Pedir importe", actions::requestAmount, Modifier.weight(1f), style = NeoButtonStyle.Tonal)
                    else NeoButton("Quitar importe", actions::clearAmount, Modifier.weight(1f), style = NeoButtonStyle.Tonal)
                    NeoButton("Compartir", actions::share, Modifier.weight(1f), icon = Ds.drawable.ic_share, enabled = state.payload != null)
                }
            }
        }
    }
    state.asking?.let { AmountSheet(it, state.currency, actions) }
}

/** The amount the code asks for; ENZONA issues a new code with it. */
@Composable
private fun AmountSheet(input: String, currency: String, actions: ReceiveActions) {
    val ready = input.replace(',', '.').toBigDecimalOrNull()?.let { it.signum() > 0 && it.scale() <= 2 } == true
    NeoBottomSheet(onDismissRequest = actions::closeAmount, title = "Pedir importe") {
        Column(Modifier.padding(horizontal = NeoSpace.gutter), verticalArrangement = Arrangement.spacedBy(NeoSpace.l)) {
            NeoAmountField(input, actions::amount, currency)
            NeoButton("Mostrar importe", actions::showAmount, enabled = ready)
        }
    }
}

/** QR modules drawn as squares, high error correction so the mark in the middle never breaks the read. */
@Composable
fun QrCode(payload: String, modifier: Modifier = Modifier, color: Color = Color(0xFF111316)) {
    val matrix = remember(payload) { encode(payload) }
    Canvas(modifier) {
        val cell = size.minDimension / matrix.width
        val offset = (size.minDimension - cell * matrix.width) / 2
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(color, Offset(offset + x * cell, offset + y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

private fun encode(payload: String): BitMatrix = QRCodeWriter().encode(
    payload, BarcodeFormat.QR_CODE, 0, 0,
    mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H, EncodeHintType.MARGIN to 0, EncodeHintType.CHARACTER_SET to "UTF-8"),
)
