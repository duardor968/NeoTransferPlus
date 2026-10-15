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
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dev.duardo.neotransfer.plus.designsystem.R as Ds
import dev.duardo.neotransfer.plus.designsystem.component.AmountDisplay
import dev.duardo.neotransfer.plus.designsystem.component.KeypadKey
import dev.duardo.neotransfer.plus.designsystem.component.MoneyText
import dev.duardo.neotransfer.plus.designsystem.component.NeoButton
import dev.duardo.neotransfer.plus.designsystem.component.NeoButtonStyle
import dev.duardo.neotransfer.plus.designsystem.component.NeoKeypad
import dev.duardo.neotransfer.plus.designsystem.component.NeoTopBar
import dev.duardo.neotransfer.plus.designsystem.theme.Neo
import dev.duardo.neotransfer.plus.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.plus.ui.common.BottomAction
import dev.duardo.neotransfer.plus.ui.common.BrandMark
import dev.duardo.neotransfer.plus.ui.model.ReceiveUi

interface ReceiveActions {
    fun back()
    fun key(key: KeypadKey)
    fun create()
    fun otherAmount()
    fun share()
}

/** Cobrar: the amount, then the QR that ENZONA issues for it, for the other person to scan and pay. */
@Composable
fun ReceiveScreen(state: ReceiveUi, actions: ReceiveActions, modifier: Modifier = Modifier) {
    Scaffold(
        modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { NeoTopBar("Cobrar", onBack = actions::back) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (state) {
                is ReceiveUi.Amount -> {
                    Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = NeoSpace.gutter), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        AmountDisplay(state.input, state.currency)
                        state.error?.let {
                            Text(it, Modifier.padding(top = NeoSpace.s), style = MaterialTheme.typography.bodyMedium, color = Neo.colors.danger)
                        }
                    }
                    NeoKeypad(actions::key, Modifier.padding(horizontal = NeoSpace.m, vertical = NeoSpace.s))
                    BottomAction {
                        val ready = state.input.replace(',', '.').toBigDecimalOrNull()?.signum() == 1
                        NeoButton("Crear código", actions::create, icon = Ds.drawable.ic_qr_code, enabled = ready, loading = state.creating)
                    }
                }
                is ReceiveUi.Code -> {
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(Modifier.height(NeoSpace.xl))
                        MoneyText(state.amount.amount, state.amount.currency, style = MaterialTheme.typography.displaySmall, hero = true)
                        state.owner?.let {
                            Text(it, Modifier.padding(top = NeoSpace.xs), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(NeoSpace.xl))
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
                            QrCode(state.payload, Modifier.fillMaxSize().semantics { contentDescription = "Código QR para cobrar" })
                            Box(Modifier.size(64.dp).background(Color.White, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                                BrandMark(52.dp)
                            }
                        }
                        Spacer(Modifier.height(NeoSpace.xl))
                    }
                    BottomAction {
                        Row(horizontalArrangement = Arrangement.spacedBy(NeoSpace.s)) {
                            NeoButton("Otro importe", actions::otherAmount, Modifier.weight(1f), style = NeoButtonStyle.Tonal)
                            NeoButton("Compartir", actions::share, Modifier.weight(1f), icon = Ds.drawable.ic_share)
                        }
                    }
                }
            }
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
