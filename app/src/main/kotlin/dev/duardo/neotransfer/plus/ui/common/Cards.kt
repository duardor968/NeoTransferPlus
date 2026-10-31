package dev.duardo.neotransfer.plus.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.plus.R
import dev.duardo.neotransfer.plus.designsystem.R as Ds
import dev.duardo.neotransfer.plus.designsystem.card.CardAspectRatio
import dev.duardo.neotransfer.plus.designsystem.card.CardFace
import dev.duardo.neotransfer.plus.designsystem.card.CardFaceLayout
import dev.duardo.neotransfer.plus.designsystem.card.CardPrint
import dev.duardo.neotransfer.plus.designsystem.card.CardShape
import dev.duardo.neotransfer.plus.designsystem.card.PlainCardFace
import dev.duardo.neotransfer.plus.designsystem.format.NoBreak
import dev.duardo.neotransfer.plus.designsystem.format.formatCardNumber
import dev.duardo.neotransfer.plus.designsystem.theme.Neo
import dev.duardo.neotransfer.plus.ui.model.CardArt
import dev.duardo.neotransfer.plus.ui.model.CardUi
import dev.duardo.neotransfer.plus.ui.model.ForeignCardUi

/**
 * Print positions measured on rectified photos of physical cards (ID-1, card-printer output, Arial metrics):
 * number centred in its band, then holder, then currency and expiry on one line. BANDEC prints lower than BPA;
 * Metropolitano prints a larger number and puts the currency alone under the holder, by the bottom edge.
 */
private val BpaPrint = CardFaceLayout(
    number = CardPrint(x = 0.107f, baseline = 0.582f, size = 0.083f, maxWidth = 0.84f),
    holder = CardPrint(x = 0.093f, baseline = 0.794f, size = 0.041f, maxWidth = 0.68f),
    currency = CardPrint(x = 0.059f, baseline = 0.895f, size = 0.041f, maxWidth = 0.20f),
    expiry = CardPrint(x = 0.303f, baseline = 0.895f, size = 0.041f, maxWidth = 0.45f),
)

private val BandecPrint = CardFaceLayout(
    number = CardPrint(x = 0.107f, baseline = 0.600f, size = 0.083f, maxWidth = 0.84f),
    holder = CardPrint(x = 0.092f, baseline = 0.830f, size = 0.041f, maxWidth = 0.70f),
    currency = CardPrint(x = 0.057f, baseline = 0.926f, size = 0.041f, maxWidth = 0.20f),
    expiry = CardPrint(x = 0.302f, baseline = 0.926f, size = 0.041f, maxWidth = 0.45f),
)

private val BanmetPrint = CardFaceLayout(
    number = CardPrint(x = 0.119f, baseline = 0.588f, size = 0.088f, maxWidth = 0.80f),
    holder = CardPrint(x = 0.100f, baseline = 0.785f, size = 0.041f, maxWidth = 0.68f),
    currency = CardPrint(x = 0.085f, baseline = 0.963f, size = 0.041f, maxWidth = 0.14f),
    expiry = CardPrint(x = 0.309f, baseline = 0.905f, size = 0.041f, maxWidth = 0.45f),
)

@get:DrawableRes
private val CardArt.drawable: Int?
    get() = when (this) {
        CardArt.BPA -> R.drawable.card_bpa_red
        CardArt.BANDEC -> R.drawable.card_bandec_red
        CardArt.BANMET -> R.drawable.card_banmet_red
        CardArt.Neutral -> null
    }

/** The dominant colour of a card's art, for the light around it on Home; null for the neutral face. */
val CardArt.artColor: Color?
    get() = when (this) {
        CardArt.BPA -> Color(0xFF1E7A5E)
        CardArt.BANDEC -> Color(0xFFCC2A45)
        CardArt.BANMET -> Color(0xFF90D200)
        CardArt.Neutral -> null
    }

private val CardArt.print: CardFaceLayout
    get() = when (this) {
        CardArt.BANDEC -> BandecPrint
        CardArt.BANMET -> BanmetPrint
        CardArt.BPA, CardArt.Neutral -> BpaPrint
    }

/** The full number grouped as printed; with only the last four known, the rest of the band stays dotted. */
val CardUi.printedNumber: String
    get() = number?.let(::formatCardNumber) ?: "•••• •••• •••• ${last4 ?: "••••"}"

/** The number in a row: in full when known, otherwise its last four. */
val CardUi.numberLine: String
    get() = number?.let(::formatCardNumber) ?: last4?.let { "•••• $it" } ?: bank

/** The user's card, 1:1 with the plastic when its art exists; otherwise a neutral face with the bank's name. */
@Composable
fun BankCard(card: CardUi, modifier: Modifier = Modifier, elevation: Dp = 10.dp, onClick: (() -> Unit)? = null) {
    val description = "Tarjeta ${card.bank} ${card.numberLine}, ${card.currency.uppercase()}"
    val art = card.art.drawable
    if (art != null) {
        CardFace(
            art = painterResource(art),
            layout = card.art.print,
            number = card.printedNumber,
            holder = card.holder,
            expiry = card.expiry,
            currency = card.currency,
            contentDescription = description,
            modifier = modifier,
            elevation = elevation,
            onClick = onClick,
        )
    } else {
        PlainCardFace(
            bank = card.bank,
            number = card.printedNumber,
            holder = card.holder,
            expiry = card.expiry,
            currency = card.currency,
            contentDescription = description,
            modifier = modifier,
            elevation = elevation,
            onClick = onClick,
        )
    }
}

/** Card art without print, for list rows and pickers. */
@Composable
fun CardThumb(card: CardUi, modifier: Modifier = Modifier, width: Dp = 44.dp) {
    val shape = RoundedCornerShape(percent = 10)
    val framed = modifier.width(width).aspectRatio(CardAspectRatio).clip(shape).border(1.dp, Neo.colors.divider, shape)
    val art = card.art.drawable
    if (art != null) {
        Image(painterResource(art), null, framed, contentScale = ContentScale.FillBounds)
    } else {
        Box(framed.background(Brush.linearGradient(listOf(Color(0xFF3A3F47), Color(0xFF15171A))))) {
            Box(
                Modifier.align(Alignment.CenterStart).offset(x = width * 0.12f).fillMaxWidth(0.2f).fillMaxHeight(0.24f)
                    .background(Color(0xFFC9B47A), RoundedCornerShape(2.dp)),
            )
        }
    }
}

/** Someone else's card in a row: its bank's art when the number tells the bank, a blank card otherwise. */
@Composable
fun ForeignCardThumb(card: ForeignCardUi, modifier: Modifier = Modifier, width: Dp = 44.dp) {
    val shape = RoundedCornerShape(percent = 10)
    val art = card.art.drawable
    val framed = modifier.width(width).aspectRatio(CardAspectRatio).clip(shape).border(1.dp, Neo.colors.divider, shape)
    if (art != null) {
        Image(painterResource(art), null, framed, contentScale = ContentScale.FillBounds)
    } else {
        Box(framed.background(Neo.colors.neutralContainer), contentAlignment = Alignment.Center) {
            Icon(painterResource(Ds.drawable.ic_credit_card), null, Modifier.size(width * 0.4f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Someone else's card as it will look: its bank's face once the number tells the bank, a blank card until then. */
@Composable
fun ForeignCardFace(card: ForeignCardUi, modifier: Modifier = Modifier) {
    val art = card.art.drawable
    if (art != null) {
        CardFace(
            art = painterResource(art),
            layout = card.art.print,
            number = formatCardNumber(card.number),
            currency = card.currency,
            contentDescription = "Tarjeta ${card.bank} ${formatCardNumber(card.number)}",
            modifier = modifier,
            elevation = 6.dp,
        )
        return
    }
    BoxWithConstraints(
        modifier.fillMaxWidth().aspectRatio(CardAspectRatio).clip(CardShape).background(Neo.colors.neutralContainer).clearAndSetSemantics { },
    ) {
        val print = BpaPrint.number
        Text(
            formatCardNumber(card.number).ifEmpty { List(4) { "••••" }.joinToString(NoBreak.toString()) },
            Modifier.padding(start = maxWidth * print.x).align(Alignment.CenterStart),
            style = MaterialTheme.typography.titleLarge.copy(fontSize = with(LocalDensity.current) { (maxWidth * print.size).toSp() }),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (card.number.isEmpty()) 0.4f else 1f),
            maxLines = 1,
        )
    }
}
