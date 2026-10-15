package dev.duardo.neotransfer.plus.ui.pay

import android.app.Activity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import dev.duardo.neotransfer.plus.designsystem.R as Ds
import dev.duardo.neotransfer.plus.designsystem.component.IconAvatar
import dev.duardo.neotransfer.plus.designsystem.component.MoneyText
import dev.duardo.neotransfer.plus.designsystem.component.NeoButton
import dev.duardo.neotransfer.plus.designsystem.component.NeoButtonStyle
import dev.duardo.neotransfer.plus.designsystem.component.NeoTextField
import dev.duardo.neotransfer.plus.designsystem.format.formatMoney
import dev.duardo.neotransfer.plus.designsystem.theme.Neo
import dev.duardo.neotransfer.plus.designsystem.theme.NeoRadius
import dev.duardo.neotransfer.plus.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.plus.ui.common.SourceCardPicker
import dev.duardo.neotransfer.plus.ui.model.PayQrUi
import dev.duardo.neotransfer.plus.ui.model.PayReviewUi
import java.math.BigDecimal

interface PayQrActions {
    fun amount(value: String)
    fun description(value: String)
    fun source(index: Int)
    fun pay()
    fun rescan()
}

/**
 * Pay a merchant QR. The camera fills the screen ([cameraContent] is the scanner, with its own close, torch and
 * gallery); once ENZONA resolves the code, the review rises from the bottom with only what can still change.
 */
@Composable
fun PayQrScreen(state: PayQrUi, actions: PayQrActions, cameraContent: @Composable () -> Unit, modifier: Modifier = Modifier) {
    LightSystemIcons()
    Box(modifier.fillMaxSize().background(Color.Black)) {
        cameraContent()
        Text(
            "Pagar",
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 22.dp),
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
        )
        AnimatedVisibility(
            visible = state !is PayQrUi.Scanning,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            ReviewPanel {
                AnimatedContent(state, transitionSpec = { fadeIn() togetherWith fadeOut() }, contentKey = { it::class }, label = "panel") { current ->
                    when (current) {
                        PayQrUi.Resolving -> Resolving()
                        is PayQrUi.Review -> Review(current.review, actions)
                        is PayQrUi.Unsupported -> Unsupported(current.message, actions)
                        PayQrUi.Scanning -> Unit
                    }
                }
            }
        }
    }
}

/** Over the camera the status bar icons are always light, whatever the theme. */
@Composable
private fun LightSystemIcons() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previous = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        onDispose { if (previous != null) controller.isAppearanceLightStatusBars = previous }
    }
}

@Composable
private fun ReviewPanel(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Neo.colors.group, RoundedCornerShape(topStart = NeoRadius.sheet, topEnd = NeoRadius.sheet))
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = NeoSpace.gutter)
            .padding(top = NeoSpace.s, bottom = NeoSpace.l),
    ) {
        Box(
            Modifier.align(Alignment.CenterHorizontally).padding(vertical = NeoSpace.s).size(width = 32.dp, height = 4.dp)
                .background(MaterialTheme.colorScheme.outlineVariant, CircleShape),
        )
        content()
    }
}

@Composable
private fun Resolving() {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(vertical = NeoSpace.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NeoSpace.m),
    ) {
        Box(Modifier.size(44.dp).background(Neo.colors.neutralContainer, CircleShape), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Neo.colors.accentInk)
        }
        Text("Consultando el código en ENZONA", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Review(review: PayReviewUi, actions: PayQrActions) {
    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(NeoSpace.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NeoSpace.m)) {
            IconAvatar(Ds.drawable.ic_qr_code)
            Text(review.merchant, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        }
        if (review.amountEditable) {
            NeoTextField(review.amountInput, actions::amount, "Importe", keyboardType = KeyboardType.Decimal, suffix = review.currency)
        } else {
            val amount = review.amountInput.toBigDecimalOrNull() ?: BigDecimal.ZERO
            MoneyText(amount, review.currency, Modifier.padding(vertical = NeoSpace.xs), style = MaterialTheme.typography.displaySmall, hero = true)
        }
        NeoTextField(review.description, actions::description, "Concepto (opcional)", imeAction = ImeAction.Done)
        SourceCardPicker(review.sources, review.source, actions::source)
        val amount = review.amountInput.replace(',', '.').toBigDecimalOrNull()
        NeoButton(
            if (amount != null && amount.signum() > 0) "Pagar ${formatMoney(amount, review.currency)}" else "Pagar",
            actions::pay,
            icon = Ds.drawable.ic_fingerprint,
            enabled = review.canPay,
            loading = review.paying,
        )
    }
}

@Composable
private fun Unsupported(message: String, actions: PayQrActions) {
    Column(Modifier.padding(vertical = NeoSpace.s), verticalArrangement = Arrangement.spacedBy(NeoSpace.l)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NeoSpace.m)) {
            Box(Modifier.size(44.dp).background(Neo.colors.warningContainer, CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(Ds.drawable.ic_error), null, tint = Neo.colors.warning)
            }
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        }
        NeoButton("Escanear otro", actions::rescan, style = NeoButtonStyle.Tonal)
    }
}
