package dev.duardo.neotransfer.plus.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.plus.designsystem.R as Ds
import dev.duardo.neotransfer.plus.designsystem.card.CardAspectRatio
import dev.duardo.neotransfer.plus.designsystem.card.CardCarousel
import dev.duardo.neotransfer.plus.designsystem.card.CardShape
import dev.duardo.neotransfer.plus.designsystem.card.PagerDots
import dev.duardo.neotransfer.plus.designsystem.component.GroupDivider
import dev.duardo.neotransfer.plus.designsystem.component.IconAvatar
import dev.duardo.neotransfer.plus.designsystem.component.MoneyText
import dev.duardo.neotransfer.plus.designsystem.component.NeoBottomSheet
import dev.duardo.neotransfer.plus.designsystem.component.NeoButton
import dev.duardo.neotransfer.plus.designsystem.component.NeoButtonStyle
import dev.duardo.neotransfer.plus.designsystem.component.NeoGroup
import dev.duardo.neotransfer.plus.designsystem.component.NeoIconButton
import dev.duardo.neotransfer.plus.designsystem.component.NeoRow
import dev.duardo.neotransfer.plus.designsystem.component.NeoTextField
import dev.duardo.neotransfer.plus.designsystem.component.QuickAction
import dev.duardo.neotransfer.plus.designsystem.component.SectionHeader
import dev.duardo.neotransfer.plus.designsystem.component.dashedOutline
import dev.duardo.neotransfer.plus.designsystem.component.skeleton
import dev.duardo.neotransfer.plus.designsystem.theme.Neo
import dev.duardo.neotransfer.plus.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.plus.ui.common.BankCard
import dev.duardo.neotransfer.plus.ui.common.BrandMark
import dev.duardo.neotransfer.plus.ui.common.CardNumberTransformation
import dev.duardo.neotransfer.plus.ui.common.OperationAmount
import dev.duardo.neotransfer.plus.ui.common.OperationAvatar
import dev.duardo.neotransfer.plus.ui.common.OperationRow
import dev.duardo.neotransfer.plus.ui.common.numberLine
import dev.duardo.neotransfer.plus.ui.common.statusText
import dev.duardo.neotransfer.plus.ui.common.timeLabels
import dev.duardo.neotransfer.plus.ui.model.BalanceUi
import dev.duardo.neotransfer.plus.ui.model.CardSheetUi
import dev.duardo.neotransfer.plus.ui.model.HomeUi
import dev.duardo.neotransfer.plus.ui.model.OperationStatus
import dev.duardo.neotransfer.plus.ui.model.OperationUi
import dev.duardo.neotransfer.plus.ui.model.RefreshState
import dev.duardo.neotransfer.plus.ui.model.WalletCardUi
import java.time.Instant
import kotlinx.coroutines.flow.distinctUntilChanged

interface HomeActions {
    fun refresh()
    fun selectCard(index: Int)
    fun refreshBalance(cardId: String)
    fun openCard(cardId: String)
    fun transfer()
    fun pay()
    fun receive()
    fun openOperation(id: String)
    fun allActivity()
    fun settings()
}

interface CardSheetActions {
    fun number(value: String)
    fun save()
    fun copy()
    fun movements()
    fun close()
}

private const val RecentLimit = 5

/**
 * Wallet: the card in hand, its balance, the three shortcuts, what is still in flight and what just happened.
 * Pulling down reads ENZONA again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: HomeUi, actions: HomeActions, modifier: Modifier = Modifier, sheet: CardSheetUi? = null, sheetActions: CardSheetActions? = null) {
    val pages = state.cards.size.coerceAtLeast(1)
    val pager = rememberPagerState(initialPage = state.selected.coerceIn(0, pages - 1)) { pages }
    val latest by rememberUpdatedState(state)
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { page ->
            if (page < latest.cards.size && page != latest.selected) actions.selectCard(page)
        }
    }
    val current = state.cards.getOrNull(pager.currentPage)
    val labels = timeLabels
    val pull = rememberPullToRefreshState()
    val refreshing = state.refresh == RefreshState.Refreshing

    PullToRefreshBox(
        isRefreshing = refreshing && state.cards.isNotEmpty(),
        onRefresh = actions::refresh,
        modifier = modifier.fillMaxSize(),
        state = pull,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pull,
                isRefreshing = refreshing && state.cards.isNotEmpty(),
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
                containerColor = Neo.colors.group,
                color = Neo.colors.accentInk,
            )
        },
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = NeoSpace.xxl)) {
            item(key = "header") {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(start = NeoSpace.gutter, end = NeoSpace.xs).heightIn(min = 64.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BrandMark(36.dp)
                    Spacer(Modifier.weight(1f))
                    NeoIconButton(Ds.drawable.ic_settings, "Ajustes", actions::settings)
                }
            }
            item(key = "wallet") {
                CardCarousel(pager, key = { state.cards.getOrNull(it)?.card?.id ?: "empty" }) { page ->
                    val wallet = state.cards.getOrNull(page)
                    when {
                        wallet != null -> BankCard(wallet.card, onClick = { actions.openCard(wallet.card.id) })
                        state.recent == null -> Box(Modifier.fillMaxWidth().aspectRatio(CardAspectRatio).skeleton(CardShape))
                        else -> NoCardsSlot()
                    }
                }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { PagerDots(state.cards.size, pager.currentPage) }
            }
            item(key = "balance") {
                AnimatedContent(
                    current,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    contentKey = { it?.card?.id },
                    label = "balance",
                ) { wallet ->
                    Box(Modifier.fillMaxWidth().heightIn(min = if (wallet != null) 112.dp else NeoSpace.l), contentAlignment = Alignment.Center) {
                        if (wallet != null) Balance(wallet, state.refresh == RefreshState.Failed, labels::ago) { actions.refreshBalance(wallet.card.id) }
                    }
                }
            }
            item(key = "actions") {
                Row(Modifier.fillMaxWidth().padding(horizontal = NeoSpace.m, vertical = NeoSpace.s)) {
                    QuickAction(Ds.drawable.ic_arrow_outward, "Transferir", actions::transfer, Modifier.weight(1f), primary = true)
                    QuickAction(Ds.drawable.ic_qr_code_scanner, "Pagar", actions::pay, Modifier.weight(1f))
                    QuickAction(Ds.drawable.ic_south_west, "Cobrar", actions::receive, Modifier.weight(1f))
                }
            }
            if (state.inProgress.isNotEmpty()) item(key = "progress") {
                Column(Modifier.padding(horizontal = NeoSpace.gutter).padding(top = NeoSpace.l)) {
                    SectionHeader("En curso")
                    NeoGroup {
                        state.inProgress.forEachIndexed { index, operation ->
                            if (index > 0) GroupDivider()
                            InProgressRow(operation, labels::ago) { actions.openOperation(operation.id) }
                        }
                    }
                }
            }
            item(key = "recent") {
                Column(Modifier.padding(horizontal = NeoSpace.gutter).padding(top = NeoSpace.l)) {
                    val recent = state.recent
                    SectionHeader("Actividad reciente", action = if (!recent.isNullOrEmpty()) "Ver todo" else null, onAction = actions::allActivity)
                    NeoGroup {
                        when {
                            recent == null -> repeat(3) { index ->
                                if (index > 0) GroupDivider()
                                SkeletonRow()
                            }
                            recent.isEmpty() -> NeoRow(
                                "Sin movimientos",
                                leading = { IconAvatar(Ds.drawable.ic_receipt_long, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                            )
                            else -> recent.take(RecentLimit).forEachIndexed { index, operation ->
                                if (index > 0) GroupDivider()
                                OperationRow(operation, operation.at?.let(labels::moment) ?: operation.dateText.orEmpty(), { actions.openOperation(operation.id) })
                            }
                        }
                    }
                }
            }
        }
    }
    if (sheet != null && sheetActions != null) CardSheet(sheet, sheetActions)
}

/** ENZONA lists no card for this account: the slot keeps the card's footprint. */
@Composable
private fun NoCardsSlot() {
    val outline = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    Column(
        Modifier.fillMaxWidth().aspectRatio(CardAspectRatio).clip(CardShape)
            .dashedOutline(outline, maxWidth / CardAspectRatio * 0.06f).padding(NeoSpace.xl),
        verticalArrangement = Arrangement.spacedBy(NeoSpace.s, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(48.dp).border(1.5.dp, outline, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(Ds.drawable.ic_credit_card), null, tint = MaterialTheme.colorScheme.onSurface)
        }
        Text("Sin tarjetas vinculadas", style = MaterialTheme.typography.titleSmall)
        Text(
            "Se vinculan en la app de ENZONA",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
    }
}

@Composable
private fun Balance(wallet: WalletCardUi, failed: Boolean, ago: (Instant) -> String, refresh: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(NeoSpace.s)) {
        when (val balance = wallet.balance) {
            is BalanceUi.Known -> {
                MoneyText(balance.money.amount, balance.money.currency, style = MaterialTheme.typography.displayMedium, hero = true)
                val text = when {
                    wallet.refreshing -> "Consultando saldo"
                    failed -> "Sin conexión · ${ago(balance.updatedAt)}"
                    else -> "Actualizado ${ago(balance.updatedAt)}"
                }
                RefreshPill(text, wallet.refreshing, failed, refresh)
            }
            BalanceUi.Unknown -> NeoButton(
                "Consultar saldo", refresh, style = NeoButtonStyle.Tonal, icon = Ds.drawable.ic_refresh,
                loading = wallet.refreshing, fill = false,
            )
        }
    }
}

/** Freshness of the balance; tapping reads it again. After a failed read it says so in warning ink. */
@Composable
private fun RefreshPill(text: String, busy: Boolean, failed: Boolean, onClick: () -> Unit) {
    val ink = if (failed && !busy) Neo.colors.warning else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .clip(CircleShape)
            .background(if (failed && !busy) Neo.colors.warningContainer else MaterialTheme.colorScheme.surfaceContainer)
            .clickable(enabled = !busy, role = Role.Button, onClickLabel = "Consultar saldo", onClick = onClick)
            .heightIn(min = 36.dp)
            .padding(horizontal = NeoSpace.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NeoSpace.s),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.75.dp, color = ink)
        else Icon(painterResource(if (failed) Ds.drawable.ic_sync_problem else Ds.drawable.ic_refresh), null, Modifier.size(16.dp), tint = ink)
        Text(text, style = MaterialTheme.typography.labelMedium, color = ink)
    }
}

@Composable
private fun InProgressRow(operation: OperationUi, ago: (Instant) -> String, open: () -> Unit) {
    val unconfirmed = operation.status == OperationStatus.Unconfirmed
    NeoRow(
        operation.title,
        leading = { OperationAvatar(operation) },
        supportingContent = {
            Text(
                listOfNotNull(operation.statusText, operation.at?.let(ago)).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = if (unconfirmed) Neo.colors.warning else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailing = { OperationAmount(operation) },
        onClick = open,
        trailingBelowWhenLarge = true,
    )
}

@Composable
private fun SkeletonRow() {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = NeoSpace.l, vertical = NeoSpace.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NeoSpace.m),
    ) {
        Box(Modifier.size(44.dp).skeleton(CircleShape))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth(0.55f).height(14.dp).skeleton())
            Box(Modifier.fillMaxWidth(0.3f).height(12.dp).skeleton())
        }
        Box(Modifier.width(72.dp).height(14.dp).skeleton())
    }
}

/**
 * One card: its full number to copy, or the field to complete it (ENZONA only gives the last four digits), and its
 * own bank movements.
 */
@Composable
private fun CardSheet(state: CardSheetUi, actions: CardSheetActions) {
    val card = state.card
    NeoBottomSheet(onDismissRequest = actions::close, title = "Tarjeta ${card.bank}") {
        Column(Modifier.padding(horizontal = NeoSpace.xl), verticalArrangement = Arrangement.spacedBy(NeoSpace.m)) {
            if (card.number != null) {
                NeoGroup {
                    NeoRow(
                        card.numberLine,
                        fitTitle = true,
                        supporting = listOfNotNull(card.currency, card.holder).joinToString(" · "),
                        trailing = { NeoIconButton(Ds.drawable.ic_content_copy, "Copiar número", actions::copy) },
                    )
                }
            } else {
                NeoTextField(
                    state.input, actions::number, "Número completo",
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                    error = state.error,
                    supporting = card.last4?.let { "Termina en $it" },
                    visualTransformation = CardNumberTransformation,
                )
                NeoButton("Guardar número", actions::save, enabled = state.input.length == 16, loading = state.saving)
            }
            NeoGroup {
                NeoRow(
                    "Movimientos de la tarjeta",
                    leading = { IconAvatar(Ds.drawable.ic_receipt_long) },
                    trailing = { Icon(painterResource(Ds.drawable.ic_chevron_right), null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = actions::movements,
                )
            }
        }
    }
}
