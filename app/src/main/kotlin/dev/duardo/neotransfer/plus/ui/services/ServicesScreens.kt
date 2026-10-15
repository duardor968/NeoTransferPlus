package dev.duardo.neotransfer.plus.ui.services

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.plus.designsystem.R as Ds
import dev.duardo.neotransfer.plus.designsystem.component.FittedText
import dev.duardo.neotransfer.plus.designsystem.component.GroupDivider
import dev.duardo.neotransfer.plus.designsystem.component.MoneyText
import dev.duardo.neotransfer.plus.designsystem.component.NeoButton
import dev.duardo.neotransfer.plus.designsystem.component.NeoGroup
import dev.duardo.neotransfer.plus.designsystem.component.NeoRow
import dev.duardo.neotransfer.plus.designsystem.component.NeoTextField
import dev.duardo.neotransfer.plus.designsystem.component.NeoTopBar
import dev.duardo.neotransfer.plus.designsystem.component.SectionHeader
import dev.duardo.neotransfer.plus.designsystem.component.SummaryLine
import dev.duardo.neotransfer.plus.designsystem.component.SummaryLines
import dev.duardo.neotransfer.plus.designsystem.component.dashedOutline
import dev.duardo.neotransfer.plus.designsystem.component.skeleton
import dev.duardo.neotransfer.plus.designsystem.format.formatMoney
import dev.duardo.neotransfer.plus.designsystem.theme.Neo
import dev.duardo.neotransfer.plus.designsystem.theme.NeoRadius
import dev.duardo.neotransfer.plus.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.plus.ui.common.BottomAction
import dev.duardo.neotransfer.plus.ui.common.SourceCardPicker
import dev.duardo.neotransfer.plus.ui.common.icon
import dev.duardo.neotransfer.plus.ui.model.BillUi
import dev.duardo.neotransfer.plus.ui.model.Money
import dev.duardo.neotransfer.plus.ui.model.RecentReferenceUi
import dev.duardo.neotransfer.plus.ui.model.ServiceFormUi
import dev.duardo.neotransfer.plus.ui.model.ServiceKind
import dev.duardo.neotransfer.plus.ui.model.ServiceLookupUi
import dev.duardo.neotransfer.plus.ui.model.ServicesUi
import dev.duardo.neotransfer.plus.ui.model.Utility

// ---- Catalogue ----------------------------------------------------------------------------------------------

interface ServicesActions {
    fun open(kind: ServiceKind)
    fun repeat(reference: RecentReferenceUi)
}

/**
 * Services grouped by what they pay for. References already paid from this phone come first, one tap from paying
 * again; the dashed tiles start a new one.
 */
@Composable
fun ServicesScreen(state: ServicesUi, actions: ServicesActions, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = NeoSpace.xxl)) {
        item(key = "title") {
            Text(
                "Servicios",
                Modifier.statusBarsPadding().padding(horizontal = NeoSpace.gutter).padding(top = NeoSpace.xl, bottom = NeoSpace.s),
                style = MaterialTheme.typography.headlineMedium,
            )
        }
        Utility.entries.forEach { utility ->
            val kinds = ServiceKind.entries.filter { it.utility == utility }
            val recent = state.recent.filter { it.kind.utility == utility }
            item(key = utility.name) {
                Column(Modifier.padding(top = NeoSpace.m)) {
                    SectionHeader(utility.label, Modifier.padding(horizontal = NeoSpace.gutter))
                    val tiles: List<Any> = recent + kinds
                    Column(Modifier.padding(horizontal = NeoSpace.gutter), verticalArrangement = Arrangement.spacedBy(NeoSpace.s)) {
                        tiles.chunked(2).forEach { pair ->
                            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(NeoSpace.s)) {
                                pair.forEach { tile ->
                                    val m = Modifier.weight(1f).fillMaxHeight()
                                    when (tile) {
                                        is RecentReferenceUi -> ReferenceTile(tile, m) { actions.repeat(tile) }
                                        is ServiceKind -> NewPaymentTile(tile.startLabel(single = kinds.size == 1), tile.icon, m) { actions.open(tile) }
                                    }
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Within a one-service utility the header already names it. */
private fun ServiceKind.startLabel(single: Boolean): String = when {
    single -> "Pagar"
    this == ServiceKind.Nauta || this == ServiceKind.NautaHome -> "Recargar $label"
    else -> label
}

/** Starts a payment: dashed, the system's mark for "new". */
@Composable
private fun NewPaymentTile(title: String, icon: Int, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(NeoRadius.group)
    Column(
        modifier
            .heightIn(min = 96.dp)
            .clip(shape)
            .dashedOutline(MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), NeoRadius.group)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(NeoSpace.l),
        verticalArrangement = Arrangement.spacedBy(NeoSpace.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NeoSpace.xs)) {
            Icon(painterResource(Ds.drawable.ic_add), null, Modifier.size(20.dp), tint = Neo.colors.accentInk)
            Icon(painterResource(icon), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1f))
        Text(title, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun ReferenceTile(reference: RecentReferenceUi, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .heightIn(min = 96.dp)
            .clip(RoundedCornerShape(NeoRadius.group))
            .background(Neo.colors.group)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(NeoSpace.l),
        verticalArrangement = Arrangement.spacedBy(NeoSpace.xxs),
    ) {
        Icon(painterResource(reference.kind.icon), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(NeoSpace.s))
        Text(reference.kind.label, style = MaterialTheme.typography.titleSmall)
        // References are identifiers: one line, never split mid-number.
        FittedText(reference.value, MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, minScale = 0.5f)
        reference.lastPaid?.let {
            Text(formatMoney(it.amount, it.currency), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- Query and pay ------------------------------------------------------------------------------------------

interface ServiceFormActions {
    fun back()
    fun reference(value: String)
    fun lookup()
    fun bill(id: String)
    fun amount(value: String)
    fun source(index: Int)
    fun pay()
}

/** Nauta accounts are typed without the domain; the suffix shows it until the user writes one. */
val ServiceKind.domain: String? get() = if (this == ServiceKind.Nauta || this == ServiceKind.NautaHome) "@nauta.com.cu" else null

/**
 * One form for every service: the reference, what ENZONA answers for it (bills, the phone bill or the Nauta
 * account), the card and the amount. Changing the reference asks again.
 */
@Composable
fun ServiceFormScreen(state: ServiceFormUi, actions: ServiceFormActions, modifier: Modifier = Modifier) {
    val lookup = state.lookup
    val answered = lookup is ServiceLookupUi.Bills || lookup is ServiceLookupUi.PhoneBill || lookup is ServiceLookupUi.TopUp
    Scaffold(
        modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { NeoTopBar(state.kind.label, onBack = actions::back) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = NeoSpace.gutter).padding(bottom = NeoSpace.l),
                verticalArrangement = Arrangement.spacedBy(NeoSpace.m),
            ) {
                NeoTextField(
                    state.reference, actions::reference, state.kind.referenceLabel,
                    keyboardType = when (state.kind) {
                        ServiceKind.Electricity, ServiceKind.Landline -> KeyboardType.Number
                        else -> KeyboardType.Email
                    },
                    imeAction = ImeAction.Search,
                    error = state.referenceError,
                    suffix = state.kind.domain?.takeIf { '@' !in state.reference && state.reference.isNotEmpty() },
                )
                when (lookup) {
                    ServiceLookupUi.None -> Unit
                    ServiceLookupUi.Loading -> LookupSkeleton()
                    is ServiceLookupUi.Failed -> Problem(lookup.message)
                    is ServiceLookupUi.Bills -> Bills(lookup, actions)
                    is ServiceLookupUi.PhoneBill -> {
                        lookup.owner?.let { Owner(it) }
                        Column(Modifier.fillMaxWidth().padding(vertical = NeoSpace.m), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Factura", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            MoneyText(lookup.due.amount, lookup.due.currency, style = MaterialTheme.typography.displaySmall, hero = true)
                        }
                    }
                    is ServiceLookupUi.TopUp -> {
                        val lines = listOfNotNull(
                            lookup.owner?.let { SummaryLine("Titular", it) },
                            lookup.debt?.let { SummaryLine("Deuda", formatMoney(it.amount, it.currency)) },
                        )
                        if (lines.isNotEmpty()) NeoGroup { SummaryLines(lines) }
                        NeoTextField(
                            state.amount, actions::amount, "Importe",
                            keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done,
                            suffix = state.currency, error = state.amountError,
                            supporting = range(lookup),
                        )
                    }
                }
                if (answered && state.cards.isNotEmpty()) SourceCardPicker(state.cards, state.source, actions::source)
            }
            BottomAction {
                if (answered) {
                    val due = payLabel(state)
                    NeoButton(due, actions::pay, icon = Ds.drawable.ic_fingerprint, enabled = state.canPay, loading = state.paying)
                } else {
                    NeoButton("Consultar", actions::lookup, enabled = state.reference.isNotBlank() && lookup != ServiceLookupUi.Loading, loading = lookup == ServiceLookupUi.Loading)
                }
            }
        }
    }
}

private fun range(lookup: ServiceLookupUi.TopUp): String? {
    val min = lookup.min
    val max = lookup.max
    return when {
        min != null && max != null -> "De ${formatMoney(min.amount, min.currency)} a ${formatMoney(max.amount, max.currency)}"
        min != null -> "Desde ${formatMoney(min.amount, min.currency)}"
        max != null -> "Hasta ${formatMoney(max.amount, max.currency)}"
        else -> null
    }
}

private fun payLabel(state: ServiceFormUi): String {
    val verb = if (state.lookup is ServiceLookupUi.TopUp) "Recargar" else "Pagar"
    val money = when (val lookup = state.lookup) {
        is ServiceLookupUi.Bills -> lookup.bills.firstOrNull { it.id == lookup.selected }?.amount
        is ServiceLookupUi.PhoneBill -> lookup.due
        else -> state.amount.replace(',', '.').toBigDecimalOrNull()?.takeIf { it.signum() > 0 }?.let { Money(it, state.currency) }
    }
    return money?.let { "$verb ${formatMoney(it.amount, it.currency)}" } ?: verb
}

@Composable
private fun Owner(name: String) {
    NeoGroup { NeoRow(name, supporting = "Titular") }
}

@Composable
private fun Bills(lookup: ServiceLookupUi.Bills, actions: ServiceFormActions) {
    lookup.owner?.let { Owner(it) }
    if (lookup.bills.isEmpty()) {
        Problem("No hay facturas para este código")
        return
    }
    SectionHeader("Facturas")
    NeoGroup {
        lookup.bills.forEachIndexed { index, bill ->
            if (index > 0) GroupDivider()
            BillRow(bill, selected = bill.id == lookup.selected) { actions.bill(bill.id) }
        }
    }
}

@Composable
private fun BillRow(bill: BillUi, selected: Boolean, onClick: () -> Unit) {
    NeoRow(
        bill.period,
        supporting = if (bill.paid) "Pagada" else null,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NeoSpace.m)) {
                MoneyText(bill.amount.amount, bill.amount.currency, color = if (bill.paid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                if (!bill.paid) Box(
                    Modifier.size(22.dp).clip(CircleShape).background(if (selected) Neo.colors.accent else MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) Icon(painterResource(Ds.drawable.ic_check), null, Modifier.size(16.dp), tint = Neo.colors.onAccent)
                }
            }
        },
        onClick = if (bill.paid) null else onClick,
    )
}

/** What ENZONA could not answer, in place of the result. */
@Composable
fun Problem(message: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().background(Neo.colors.warningContainer, RoundedCornerShape(NeoRadius.field)).padding(NeoSpace.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NeoSpace.m),
    ) {
        Icon(painterResource(Ds.drawable.ic_error), null, Modifier.size(20.dp), tint = Neo.colors.warning)
        Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LookupSkeleton() {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(NeoRadius.group)).background(Neo.colors.group)) {
        repeat(2) { index ->
            if (index > 0) GroupDivider(inset = NeoSpace.l)
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = NeoSpace.l), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.fillMaxWidth(0.45f).height(14.dp).skeleton())
                Spacer(Modifier.weight(1f))
                Box(Modifier.width(80.dp).height(14.dp).skeleton())
            }
        }
    }
}

