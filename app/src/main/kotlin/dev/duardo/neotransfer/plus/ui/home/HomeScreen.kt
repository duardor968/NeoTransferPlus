package dev.duardo.neotransfer.plus.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import dev.duardo.neotransfer.plus.designsystem.R as Ds
import dev.duardo.neotransfer.plus.designsystem.card.AddCardSlot
import dev.duardo.neotransfer.plus.designsystem.card.CardAspectRatio
import dev.duardo.neotransfer.plus.designsystem.card.CardCarousel
import dev.duardo.neotransfer.plus.designsystem.card.CardShape
import dev.duardo.neotransfer.plus.designsystem.card.PagerDots
import dev.duardo.neotransfer.plus.designsystem.component.AvatarBadge
import dev.duardo.neotransfer.plus.designsystem.component.FittedText
import dev.duardo.neotransfer.plus.designsystem.component.GroupDivider
import dev.duardo.neotransfer.plus.designsystem.component.IconAvatar
import dev.duardo.neotransfer.plus.designsystem.component.MoneyText
import dev.duardo.neotransfer.plus.designsystem.component.NeoBottomSheet
import dev.duardo.neotransfer.plus.designsystem.component.NeoButton
import dev.duardo.neotransfer.plus.designsystem.component.NeoGroup
import dev.duardo.neotransfer.plus.designsystem.component.NeoIconButton
import dev.duardo.neotransfer.plus.designsystem.component.NeoRow
import dev.duardo.neotransfer.plus.designsystem.component.NeoTextField
import dev.duardo.neotransfer.plus.designsystem.component.QuickActionGrid
import dev.duardo.neotransfer.plus.designsystem.component.QuickActionItem
import dev.duardo.neotransfer.plus.designsystem.component.SectionHeader
import dev.duardo.neotransfer.plus.designsystem.component.glow
import dev.duardo.neotransfer.plus.designsystem.component.skeleton
import dev.duardo.neotransfer.plus.designsystem.component.spinning
import dev.duardo.neotransfer.plus.designsystem.format.NoBreak
import dev.duardo.neotransfer.plus.designsystem.theme.Neo
import dev.duardo.neotransfer.plus.designsystem.theme.NeoMotion
import dev.duardo.neotransfer.plus.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.plus.ui.common.BankCard
import dev.duardo.neotransfer.plus.ui.common.BrandMark
import dev.duardo.neotransfer.plus.ui.common.CardNumberTransformation
import dev.duardo.neotransfer.plus.ui.common.OperationAmount
import dev.duardo.neotransfer.plus.ui.common.OperationAvatar
import dev.duardo.neotransfer.plus.ui.common.OperationRow
import dev.duardo.neotransfer.plus.ui.common.artColor
import dev.duardo.neotransfer.plus.ui.common.icon
import dev.duardo.neotransfer.plus.ui.common.numberLine
import dev.duardo.neotransfer.plus.ui.common.statusText
import dev.duardo.neotransfer.plus.ui.common.timeLabels
import dev.duardo.neotransfer.plus.ui.model.BalanceUi
import dev.duardo.neotransfer.plus.ui.model.CardSheetUi
import dev.duardo.neotransfer.plus.ui.model.HomeUi
import dev.duardo.neotransfer.plus.ui.model.OperationStatus
import dev.duardo.neotransfer.plus.ui.model.OperationUi
import dev.duardo.neotransfer.plus.ui.model.ReadProblem
import dev.duardo.neotransfer.plus.ui.model.RefreshState
import dev.duardo.neotransfer.plus.ui.model.SavedReferenceUi
import dev.duardo.neotransfer.plus.ui.model.ServiceKind
import dev.duardo.neotransfer.plus.ui.model.WalletCardUi
import dev.duardo.neotransfer.plus.ui.services.domain
import java.time.Instant
import kotlinx.coroutines.flow.distinctUntilChanged

interface HomeActions {
    fun refresh()
    fun selectCard(index: Int)
    fun refreshBalance(cardId: String)
    fun openCard(cardId: String)
    fun addCard()
    fun transfer()
    fun pay()
    fun receive()
    fun recharge()
    fun rechargeTo(reference: SavedReferenceUi)
    fun rechargeNew(kind: ServiceKind)
    fun closeRecharge()
    fun openOperation(id: String)
    /** Sends again a payment that never reached ENZONA. */
    fun retry(id: String)
    fun allActivity()
    fun settings()
}

interface CardSheetActions {
    fun number(value: String)
    fun save()
    fun copy()
    fun movements()
    /** Ajustes › Tarjetas: the default card, or removing it. */
    fun settings()
    fun close()
}

private const val RecentLimit = 5

/**
 * Wallet: the card in hand, its balance, the four shortcuts, what is pending and what just happened. Pulling down
 * reads ENZONA again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: HomeUi, actions: HomeActions, modifier: Modifier = Modifier, sheet: CardSheetUi? = null, sheetActions: CardSheetActions? = null) {
    // While the cards are read for the first time, one placeholder; then every card and the slot to add one.
    val loading = state.cards.isEmpty() && state.recent == null
    val pages = if (loading) 1 else state.cards.size + 1
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
            item(key = "wallet") {
                // The card in hand lights the space around it in its own colour, behind the header and the balance too;
                // swiping blends one colour into the next.
                val strength = if (Neo.colors.isDark) 0.22f else 0.12f
                val accent = Neo.colors.accent
                fun tint(page: Int) = (state.cards.getOrNull(page)?.card?.art?.artColor ?: accent).copy(alpha = strength)
                fun halo(): Color {
                    val fraction = pager.currentPageOffsetFraction
                    val next = (pager.currentPage + if (fraction > 0) 1 else -1).coerceIn(0, pages - 1)
                    return lerp(tint(pager.currentPage), tint(next), kotlin.math.abs(fraction))
                }
                var height by remember { mutableIntStateOf(0) }
                var cardCenter by remember { mutableFloatStateOf(0f) }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .onSizeChanged { height = it.height }
                        .glow(::halo, y = { if (height > 0) cardCenter / height else 0.4f }, reach = 0.62f),
                ) {
                    Row(
                        Modifier.fillMaxWidth().statusBarsPadding().padding(start = NeoSpace.gutter, end = NeoSpace.xs).heightIn(min = 64.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BrandMark(36.dp)
                        Spacer(Modifier.weight(1f))
                        NeoIconButton(Ds.drawable.ic_settings, "Ajustes", actions::settings)
                    }
                    CardCarousel(
                        pager,
                        Modifier.onPlaced { cardCenter = it.positionInParent().y + it.size.height / 2f },
                        key = { if (it < state.cards.size) state.cards[it].card.id else "add" },
                    ) { page ->
                        val wallet = state.cards.getOrNull(page)
                        when {
                            wallet != null -> BankCard(wallet.card, onClick = { actions.openCard(wallet.card.id) })
                            loading -> Box(Modifier.fillMaxWidth().aspectRatio(CardAspectRatio).skeleton(CardShape))
                            else -> AddCardSlot("Añadir tarjeta", actions::addCard)
                        }
                    }
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { PagerDots(pages, pager.currentPage) }
                    AnimatedContent(
                        current,
                        transitionSpec = { fadeIn(tween(NeoMotion.medium)) togetherWith fadeOut(tween(NeoMotion.short)) },
                        contentKey = { it?.card?.id },
                        label = "balance",
                    ) { wallet ->
                        // At least the balance's own height: swiping to a card without one, or to the slot, moves nothing below.
                        Box(Modifier.fillMaxWidth().padding(top = NeoSpace.l).heightIn(min = 96.dp), contentAlignment = Alignment.Center) {
                            when {
                                wallet != null -> Balance(wallet, state.refresh as? RefreshState.Failed, labels::ago) { actions.refreshBalance(wallet.card.id) }
                                loading -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(NeoSpace.m)) {
                                    Box(Modifier.width(200.dp).height(44.dp).skeleton())
                                    Box(Modifier.width(180.dp).height(36.dp).skeleton(CircleShape))
                                }
                            }
                        }
                    }
                }
            }
            item(key = "actions") {
                QuickActionGrid(
                    listOf(
                        QuickActionItem(Ds.drawable.ic_arrow_outward, "Transferir", actions::transfer, primary = true),
                        QuickActionItem(Ds.drawable.ic_qr_code_scanner, "Pagar", actions::pay),
                        QuickActionItem(Ds.drawable.ic_south_west, "Cobrar", actions::receive),
                        // ENZONA recharges Nauta accounts, never a mobile: the glyph says internet.
                        QuickActionItem(Ds.drawable.ic_wifi, "Recargar", actions::recharge),
                    ),
                    Modifier.padding(horizontal = NeoSpace.gutter).padding(top = NeoSpace.xl),
                )
            }
            if (state.inProgress.isNotEmpty()) item(key = "progress") {
                Column(Modifier.padding(horizontal = NeoSpace.gutter).padding(top = NeoSpace.m)) {
                    SectionHeader("Pendientes")
                    NeoGroup {
                        state.inProgress.forEachIndexed { index, operation ->
                            if (index > 0) GroupDivider()
                            InProgressRow(operation, labels::ago, { actions.openOperation(operation.id) }, { actions.retry(operation.id) })
                        }
                    }
                }
            }
            item(key = "recent") {
                val recent = state.recent
                Column(Modifier.padding(horizontal = NeoSpace.gutter).padding(top = NeoSpace.m)) {
                    SectionHeader("Actividad reciente", action = if (!recent.isNullOrEmpty()) "Ver todo" else null, onAction = actions::allActivity)
                    // Nothing yet: one quiet line where the list will be, no box around it.
                    if (recent != null && recent.isEmpty()) {
                        Text("Sin movimientos", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else NeoGroup {
                        if (recent == null) {
                            repeat(3) { index ->
                                if (index > 0) GroupDivider()
                                SkeletonRow()
                            }
                        } else {
                            recent.take(RecentLimit).forEachIndexed { index, operation ->
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
    state.recharge?.let { targets -> RechargeSheet(targets, actions) }
}

/** The failed read, as the line under the balance's freshness says it. "Sin conexión" only when the network failed. */
val ReadProblem.label: String
    get() = when (this) {
        ReadProblem.Offline -> "Sin conexión"
        ReadProblem.Timeout -> "ENZONA tardó demasiado en responder"
        ReadProblem.Rejected -> "Error de ENZONA"
        ReadProblem.Unrecognized -> "Respuesta inesperada"
        ReadProblem.Expired -> "Sesión caducada"
        ReadProblem.Local -> "Error en el teléfono"
    }

@Composable
private fun Balance(wallet: WalletCardUi, failed: RefreshState.Failed?, ago: (Instant) -> String, refresh: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(NeoSpace.s)) {
        when (val balance = wallet.balance) {
            is BalanceUi.Known -> {
                MoneyText(balance.money.amount, balance.money.currency, style = MaterialTheme.typography.displayMedium, hero = true)
                // The pill always tells the balance's age; a read that failed after it says why apart.
                RefreshPill(if (wallet.refreshing) "Consultando saldo" else "Actualizado ${ago(balance.updatedAt)}", wallet.refreshing, refresh)
                failed?.problemFor(balance.updatedAt)?.takeUnless { wallet.refreshing }?.let { ReadProblemLine(it) }
            }
            BalanceUi.Unknown -> RefreshPill(if (wallet.refreshing) "Consultando saldo" else "Consultar saldo", wallet.refreshing, refresh, prominent = true)
        }
    }
}

/**
 * Freshness of the balance; tapping reads it again. [prominent] while no balance is known: then asking is the point
 * of the block. While ENZONA answers, the same glyph turns.
 */
@Composable
private fun RefreshPill(text: String, busy: Boolean, onClick: () -> Unit, prominent: Boolean = false) {
    val content = if (prominent) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .clip(CircleShape)
            .background(if (prominent) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer)
            .clickable(enabled = !busy, role = Role.Button, onClickLabel = "Consultar saldo", onClick = onClick)
            .heightIn(min = if (prominent) 52.dp else 36.dp)
            .padding(horizontal = if (prominent) NeoSpace.xxl else NeoSpace.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (prominent) NeoSpace.s + NeoSpace.xxs else NeoSpace.s),
    ) {
        Icon(painterResource(Ds.drawable.ic_refresh), null, Modifier.size(if (prominent) 20.dp else 16.dp).spinning(busy), tint = content)
        Text(text, style = if (prominent) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium, color = content)
    }
}

/**
 * Why the last read failed, under the balance it left as it was. Long ones wrap centred inside the margins, the glyph
 * held to the first word.
 */
@Composable
private fun ReadProblemLine(problem: ReadProblem) {
    val ink = Neo.colors.warning
    val glyph = mapOf(
        "glyph" to InlineTextContent(Placeholder(1.3.em, 1.3.em, PlaceholderVerticalAlign.TextCenter)) {
            Icon(painterResource(Ds.drawable.ic_sync_problem), null, Modifier.fillMaxSize(), tint = ink)
        },
    )
    Text(
        buildAnnotatedString {
            appendInlineContent("glyph")
            append("$NoBreak${problem.label}")
        },
        Modifier.padding(horizontal = NeoSpace.gutter),
        style = MaterialTheme.typography.labelMedium,
        color = ink,
        textAlign = TextAlign.Center,
        inlineContent = glyph,
    )
}

/**
 * A payment on its way or unconfirmed, its state and then its time; or one that never reached ENZONA, which ends
 * with the button that sends it again.
 */
@Composable
private fun InProgressRow(operation: OperationUi, ago: (Instant) -> String, open: () -> Unit, retry: () -> Unit) {
    val notSent = operation.status == OperationStatus.NotSent
    val warning = notSent || operation.status == OperationStatus.Unconfirmed
    NeoRow(
        operation.title,
        leading = { OperationAvatar(operation) },
        supportingContent = {
            // One line: state and time read together, never split.
            FittedText(
                if (notSent) operation.statusText else listOfNotNull(operation.statusText, operation.at?.let { ago(it).replaceFirstChar(Char::lowercase) }).joinToString(" "),
                MaterialTheme.typography.bodyMedium,
                color = if (warning) Neo.colors.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                minScale = 0.7f,
            )
        },
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NeoSpace.m)) {
                OperationAmount(operation)
                if (notSent) FilledTonalIconButton(
                    retry,
                    Modifier.size(40.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Neo.colors.accentContainer, contentColor = Neo.colors.onAccentContainer),
                ) { Icon(painterResource(Ds.drawable.ic_replay), "Reintentar", Modifier.size(20.dp)) }
            }
        },
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
 * One card: its full number to copy, or the field to complete it (ENZONA only gives the last four digits), its own
 * bank movements and its settings.
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
                        supporting = listOfNotNull(card.currency.uppercase(), card.holder).joinToString(" · "),
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
                    trailing = { Chevron() },
                    onClick = actions::movements,
                )
                GroupDivider()
                NeoRow(
                    "Ajustes de la tarjeta",
                    leading = { IconAvatar(Ds.drawable.ic_settings) },
                    trailing = { Chevron() },
                    onClick = actions::settings,
                )
            }
        }
    }
}

@Composable
private fun Chevron() {
    Icon(painterResource(Ds.drawable.ic_chevron_right), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Saved Nauta accounts, one tap from Home; then a new one of each kind, under its own glyph. */
@Composable
private fun RechargeSheet(targets: List<SavedReferenceUi>, actions: HomeActions) {
    NeoBottomSheet(onDismissRequest = actions::closeRecharge, title = "Recargar") {
        Column(Modifier.padding(horizontal = NeoSpace.xs)) {
            targets.forEach { target ->
                val account = target.kind.domain?.let(target.value::removeSuffix) ?: target.value
                NeoRow(
                    target.name ?: target.kind.label,
                    supporting = if (target.name != null) "${target.kind.label} · $account" else account,
                    leading = { IconAvatar(target.kind.icon) },
                    onClick = { actions.rechargeTo(target) },
                )
            }
            val added = AvatarBadge(Ds.drawable.ic_add, Neo.colors.accent, Neo.colors.onAccent)
            NeoRow("Cuenta Nauta", leading = { IconAvatar(ServiceKind.Nauta.icon, badge = added) }, onClick = { actions.rechargeNew(ServiceKind.Nauta) })
            NeoRow("Nauta Hogar", leading = { IconAvatar(ServiceKind.NautaHome.icon, badge = added) }, onClick = { actions.rechargeNew(ServiceKind.NautaHome) })
        }
    }
}
