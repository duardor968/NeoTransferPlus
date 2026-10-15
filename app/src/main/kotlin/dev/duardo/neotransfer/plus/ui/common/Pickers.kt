package dev.duardo.neotransfer.plus.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.plus.designsystem.R as Ds
import dev.duardo.neotransfer.plus.designsystem.component.NeoBottomSheet
import dev.duardo.neotransfer.plus.designsystem.component.NeoGroup
import dev.duardo.neotransfer.plus.designsystem.component.NeoRow
import dev.duardo.neotransfer.plus.designsystem.format.formatMoney
import dev.duardo.neotransfer.plus.designsystem.theme.Neo
import dev.duardo.neotransfer.plus.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.plus.ui.model.BalanceUi
import dev.duardo.neotransfer.plus.ui.model.CardUi
import dev.duardo.neotransfer.plus.ui.model.WalletCardUi

/**
 * One of the user's cards as a field: art, number and a detail line. With several cards it opens a sheet to change
 * it. Used wherever a card is chosen, so choosing looks the same everywhere.
 */
@Composable
fun CardPicker(
    cards: List<CardUi>,
    selected: Int,
    onSelect: (Int) -> Unit,
    sheetTitle: String,
    detail: (Int) -> String,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val current = cards[selected]
    val several = cards.size > 1
    NeoGroup(modifier) {
        NeoRow(
            current.numberLine,
            fitTitle = true,
            supporting = detail(selected),
            leading = { CardThumb(current, width = 52.dp) },
            trailing = if (several) ({ Icon(painterResource(Ds.drawable.ic_expand_more), "Cambiar tarjeta", tint = MaterialTheme.colorScheme.onSurfaceVariant) }) else null,
            onClick = if (several) ({ open = true }) else null,
        )
    }
    if (open) NeoBottomSheet(onDismissRequest = { open = false }, title = sheetTitle) {
        Column(Modifier.padding(horizontal = NeoSpace.s)) {
            cards.forEachIndexed { index, card ->
                NeoRow(
                    card.numberLine,
                    fitTitle = true,
                    supporting = detail(index),
                    leading = { CardThumb(card, width = 52.dp) },
                    trailing = if (index == selected) ({ Icon(painterResource(Ds.drawable.ic_check), "Seleccionada", tint = Neo.colors.accentInk) }) else null,
                    onClick = { open = false; onSelect(index) },
                )
            }
        }
    }
}

/** Card that pays, with its balance. */
@Composable
fun SourceCardPicker(cards: List<WalletCardUi>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    CardPicker(cards.map { it.card }, selected, onSelect, "Pagar desde", { cards[it].balanceLine() }, modifier)
}

fun WalletCardUi.balanceLine(): String = when (val b = balance) {
    is BalanceUi.Known -> "${card.bank} · Saldo ${formatMoney(b.money.amount, b.money.currency)}"
    BalanceUi.Unknown -> "${card.bank} · ${card.currency}"
}

/** The screen's main action, pinned above the navigation bar. */
@Composable
fun BottomAction(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = NeoSpace.gutter, vertical = NeoSpace.l),
        verticalArrangement = Arrangement.spacedBy(NeoSpace.s),
        content = content,
    )
}
