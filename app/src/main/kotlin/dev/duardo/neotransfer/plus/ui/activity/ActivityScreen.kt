package dev.duardo.neotransfer.plus.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.plus.designsystem.R as Ds
import dev.duardo.neotransfer.plus.designsystem.component.EmptyState
import dev.duardo.neotransfer.plus.designsystem.component.GroupDivider
import dev.duardo.neotransfer.plus.designsystem.component.NeoSearchField
import dev.duardo.neotransfer.plus.designsystem.component.NeoSegmented
import dev.duardo.neotransfer.plus.designsystem.component.NeoTextButton
import dev.duardo.neotransfer.plus.designsystem.component.groupItem
import dev.duardo.neotransfer.plus.designsystem.component.skeleton
import dev.duardo.neotransfer.plus.designsystem.theme.Neo
import dev.duardo.neotransfer.plus.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.plus.ui.common.CardThumb
import dev.duardo.neotransfer.plus.ui.common.OperationRow
import dev.duardo.neotransfer.plus.ui.common.numberLine
import dev.duardo.neotransfer.plus.ui.common.timeLabels
import dev.duardo.neotransfer.plus.ui.model.ActivityContent
import dev.duardo.neotransfer.plus.ui.model.ActivityFilter
import dev.duardo.neotransfer.plus.ui.model.ActivitySource
import dev.duardo.neotransfer.plus.ui.model.ActivityUi
import dev.duardo.neotransfer.plus.ui.model.RefreshState
import java.time.format.DateTimeFormatter
import java.util.Locale

interface ActivityActions {
    fun source(value: ActivitySource)
    fun query(value: String)
    fun filter(value: ActivityFilter)
    fun card(cardId: String?)
    fun open(id: String)
    fun refresh()
    fun loadOlder()
}

private val monthDay = DateTimeFormatter.ofPattern("d 'de' MMMM", Locale.forLanguageTag("es"))

/**
 * Two records, newest first and grouped by day: the ENZONA account (what was paid, sent and received through
 * ENZONA) and each card's own bank record. Pulling down reads ENZONA again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(state: ActivityUi, actions: ActivityActions, modifier: Modifier = Modifier) {
    val labels = timeLabels
    val account = state.source == ActivitySource.Account
    val searchable = account && state.content != ActivityContent.Empty
    val pull = rememberPullToRefreshState()
    val refreshing = state.refresh == RefreshState.Refreshing && state.content != ActivityContent.Loading
    Column(modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().padding(horizontal = NeoSpace.gutter)) {
            Row(Modifier.fillMaxWidth().padding(top = NeoSpace.m, bottom = NeoSpace.s), verticalAlignment = Alignment.CenterVertically) {
                Text("Actividad", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                if (!account && state.cards.size > 1) CardFilter(state, actions)
            }
            NeoSegmented(ActivitySource.entries, state.source, { it.label }, actions::source, Modifier.padding(bottom = NeoSpace.m))
            if (searchable) NeoSearchField(state.query, actions::query, "Buscar nombre, servicio o importe")
        }
        if (searchable) Filters(state, actions)
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = actions::refresh,
            modifier = Modifier.fillMaxSize(),
            state = pull,
            indicator = {
                PullToRefreshDefaults.Indicator(pull, refreshing, Modifier.align(Alignment.TopCenter), containerColor = Neo.colors.group, color = Neo.colors.accentInk)
            },
        ) {
            when (val content = state.content) {
                ActivityContent.Loading -> LoadingList()
                ActivityContent.Empty -> Column(Modifier.fillMaxSize()) {
                    EmptyState(
                        if (account) Ds.drawable.ic_receipt_long else Ds.drawable.ic_credit_card,
                        if (account) "Sin movimientos en los últimos días" else "Sin movimientos en esta tarjeta",
                        action = if (account) olderLabel(state) else null,
                        onAction = if (account) actions::loadOlder else null,
                    )
                }
                ActivityContent.NoResults -> Column(Modifier.fillMaxSize()) { EmptyState(Ds.drawable.ic_search, "Nada coincide con la búsqueda") }
                is ActivityContent.Items -> {
                    val days = content.operations.groupBy { it.at?.let(labels::date) }
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = NeoSpace.gutter, end = NeoSpace.gutter, bottom = NeoSpace.xxl),
                    ) {
                        days.forEach { (day, operations) ->
                            item(key = "day-$day") {
                                Text(
                                    day?.let(labels::day) ?: "Sin fecha",
                                    Modifier.padding(top = NeoSpace.xl, bottom = NeoSpace.s),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            itemsIndexed(operations, key = { _, it -> it.id }) { index, operation ->
                                Column(Modifier.groupItem(index, operations.size, Neo.colors.group)) {
                                    if (index > 0) GroupDivider()
                                    OperationRow(operation, operation.at?.let(labels::clockTime) ?: operation.dateText.orEmpty(), { actions.open(operation.id) })
                                }
                            }
                        }
                        if (account && state.olderFrom != null && state.query.isEmpty()) item(key = "older") {
                            Box(Modifier.fillMaxWidth().padding(top = NeoSpace.l), contentAlignment = Alignment.Center) {
                                if (state.loadingOlder) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = Neo.colors.accentInk)
                                else NeoTextButton(olderLabel(state), actions::loadOlder)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** "Ver desde el 16 de septiembre": the next window ENZONA is asked for. */
private fun olderLabel(state: ActivityUi): String =
    state.olderFrom?.let { "Ver desde el ${monthDay.format(it.minusDays(29))}" } ?: "Ver anteriores"

/** Which card's record: art of the chosen card. */
@Composable
private fun CardFilter(state: ActivityUi, actions: ActivityActions) {
    var open by remember { mutableStateOf(false) }
    val selected = state.cards.firstOrNull { it.id == state.card } ?: state.cards.first()
    Box {
        Row(
            Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .clickable(role = Role.Button, onClickLabel = "Elegir tarjeta") { open = true }
                .heightIn(min = 40.dp)
                .padding(start = NeoSpace.m, end = NeoSpace.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NeoSpace.s),
        ) {
            CardThumb(selected, width = 34.dp)
            Icon(painterResource(Ds.drawable.ic_expand_more), null, Modifier.size(20.dp))
        }
        DropdownMenu(open, { open = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            state.cards.forEach { card ->
                DropdownMenuItem(
                    { Text(card.numberLine) },
                    { open = false; actions.card(card.id) },
                    leadingIcon = { CardThumb(card, width = 34.dp) },
                )
            }
        }
    }
}

@Composable
private fun Filters(state: ActivityUi, actions: ActivityActions) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = NeoSpace.gutter).padding(top = NeoSpace.m),
        horizontalArrangement = Arrangement.spacedBy(NeoSpace.s),
    ) {
        ActivityFilter.entries.forEach { filter ->
            FilterChip(
                selected = state.filter == filter,
                onClick = { actions.filter(filter) },
                label = { Text(filter.label) },
                shape = CircleShape,
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    labelColor = MaterialTheme.colorScheme.onSurface,
                    selectedContainerColor = Neo.colors.accentContainer,
                    selectedLabelColor = Neo.colors.onAccentContainer,
                ),
                border = null,
            )
        }
    }
}

@Composable
private fun LoadingList() {
    Column(Modifier.padding(horizontal = NeoSpace.gutter)) {
        Box(Modifier.padding(top = NeoSpace.xxl, bottom = NeoSpace.m).width(64.dp).height(14.dp).skeleton())
        Column(Modifier.groupItem(0, 1, Neo.colors.group)) {
            repeat(6) { index ->
                if (index > 0) GroupDivider()
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = NeoSpace.l, vertical = NeoSpace.m),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NeoSpace.m),
                ) {
                    Box(Modifier.size(44.dp).skeleton(CircleShape))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.fillMaxWidth(0.6f).height(14.dp).skeleton())
                        Box(Modifier.fillMaxWidth(0.25f).height(12.dp).skeleton())
                    }
                    Box(Modifier.width(76.dp).height(14.dp).skeleton())
                }
            }
        }
    }
}
