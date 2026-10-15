package dev.duardo.neotransfer.plus.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.plus.R
import dev.duardo.neotransfer.plus.designsystem.card.CardAspectRatio
import dev.duardo.neotransfer.plus.designsystem.card.CardFace
import dev.duardo.neotransfer.plus.designsystem.card.CardFaceLayout
import dev.duardo.neotransfer.plus.designsystem.card.CardPrint
import dev.duardo.neotransfer.plus.designsystem.card.PlainCardFace
import dev.duardo.neotransfer.plus.designsystem.format.formatCardNumber
import dev.duardo.neotransfer.plus.designsystem.theme.Neo
import dev.duardo.neotransfer.plus.ui.model.CardArt
import dev.duardo.neotransfer.plus.ui.model.CardUi

/**
 * Print positions measured on rectified photos of physical cards (ID-1, card-printer output, Arial metrics):
 * number centred in its band, then holder, then currency and expiry on one line. BANDEC prints lower than BPA.
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

@get:DrawableRes
private val CardArt.drawable: Int?
    get() = when (this) {
        CardArt.BPA -> R.drawable.card_bpa_red
        CardArt.BANDEC -> R.drawable.card_bandec_red
        CardArt.Neutral -> null
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
    val description = "Tarjeta ${card.bank} ${card.numberLine}, ${card.currency}"
    val art = card.art.drawable
    if (art != null) {
        CardFace(
            art = painterResource(art),
            layout = if (card.art == CardArt.BPA) BpaPrint else BandecPrint,
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
