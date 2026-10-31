package dev.duardo.neotransfer.plus.ui.services

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.TextAutoSize
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.duardo.neotransfer.plus.designsystem.R as Ds
import dev.duardo.neotransfer.plus.designsystem.component.GroupDivider
import dev.duardo.neotransfer.plus.designsystem.component.IconAvatar
import dev.duardo.neotransfer.plus.designsystem.component.MoneyText
import dev.duardo.neotransfer.plus.designsystem.component.NeoAmountField
import dev.duardo.neotransfer.plus.designsystem.component.NeoBottomSheet
import dev.duardo.neotransfer.plus.designsystem.component.NeoChip
import dev.duardo.neotransfer.plus.designsystem.component.NeoButton
import dev.duardo.neotransfer.plus.designsystem.component.NeoConfirmSheet
import dev.duardo.neotransfer.plus.designsystem.component.NeoGroup
import dev.duardo.neotransfer.plus.designsystem.component.NeoIconButton
import dev.duardo.neotransfer.plus.designsystem.component.NeoRow
import dev.duardo.neotransfer.plus.designsystem.component.NeoTextField
import dev.duardo.neotransfer.plus.designsystem.component.NeoTopBar
import dev.duardo.neotransfer.plus.designsystem.component.QuickActionGrid
import dev.duardo.neotransfer.plus.designsystem.component.QuickActionItem
import dev.duardo.neotransfer.plus.designsystem.component.SectionHeader
import dev.duardo.neotransfer.plus.designsystem.component.TabHeader
import dev.duardo.neotransfer.plus.designsystem.component.rim
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
import dev.duardo.neotransfer.plus.ui.model.SavedEditUi
import dev.duardo.neotransfer.plus.ui.model.SavedReferenceUi
import dev.duardo.neotransfer.plus.ui.model.ServiceFormUi
import dev.duardo.neotransfer.plus.ui.model.ServiceKind
import dev.duardo.neotransfer.plus.ui.model.ServiceLookupUi
import dev.duardo.neotransfer.plus.ui.model.ServicesUi

// ---- Catalogue ----------------------------------------------------------------------------------------------

interface ServicesActions {
    fun open(kind: ServiceKind)
    fun pay(reference: SavedReferenceUi)
}

/** What can be paid: Guardados first, one tap from paying the usual bill; under them every service as a shortcut. */
@Composable
fun ServicesScreen(state: ServicesUi, actions: ServicesActions, modifier: Modifier = Modifier) {
    val saved = state.saved
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = NeoSpace.xxl)) {
        item(key = "title") { TabHeader("Servicios") }
        if (saved.isNotEmpty()) item(key = "saved") {
            Column(Modifier.padding(horizontal = NeoSpace.gutter)) {
                SectionHeader("Guardados")
                Column(verticalArrangement = Arrangement.spacedBy(NeoSpace.s)) {
                    saved.chunked(2).forEach { pair ->
                        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(NeoSpace.s)) {
                            pair.forEach { reference -> SavedTile(reference, Modifier.weight(1f).fillMaxHeight()) { actions.pay(reference) } }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        item(key = "services") {
            Column(Modifier.padding(horizontal = NeoSpace.gutter).padding(top = if (saved.isEmpty()) NeoSpace.s else NeoSpace.xxl)) {
                if (saved.isNotEmpty()) SectionHeader("Pagar un servicio")
                QuickActionGrid(ServiceKind.entries.map { kind -> QuickActionItem(kind.icon, kind.label, { actions.open(kind) }) })
            }
        }
    }
}

/** A saved reference as the bill it pays: its name (or the service), the reference, and what went last time. */
@Composable
private fun SavedTile(reference: SavedReferenceUi, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(NeoRadius.group)
    Column(
        modifier
            .heightIn(min = 112.dp)
            .clip(shape)
            .background(Neo.colors.group)
            .rim(shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(NeoSpace.l),
    ) {
        IconAvatar(reference.kind.icon, size = 40.dp)
        Spacer(Modifier.height(NeoSpace.m).weight(1f, fill = false))
        Text(reference.name ?: reference.kind.label, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        // A Nauta account under a Nauta tile needs no domain; references are identifiers: they shrink, never wrap or cut.
        Text(
            reference.kind.domain?.let(reference.value::removeSuffix) ?: reference.value,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            softWrap = false, autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = MaterialTheme.typography.bodySmall.fontSize),
        )
        // The amount never splits; when the line is short it goes under its label.
        reference.lastPaid?.let {
            Text(
                "Último pago ${formatMoney(it.amount, it.currency)}", Modifier.padding(top = NeoSpace.xxs),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
            )
        }
    }
}

// ---- Query and pay ------------------------------------------------------------------------------------------

interface ServiceFormActions {
    fun back()
    fun reference(value: String)
    /** A saved reference, or null for «Nueva». */
    fun pick(reference: SavedReferenceUi?)
    fun lookup()
    fun bill(id: String)
    fun amount(value: String)
    fun source(index: Int)
    fun pay()
    /** The sheet of a saved reference: its name, «Guardar» and «Quitar de Guardados», which asks first. */
    fun editSaved()
    fun savedName(value: String)
    fun saveSaved()
    fun removeSaved()
    fun confirmRemoveSaved()
    fun cancelRemoveSaved()
    fun closeSaved()
}

/** Nauta accounts may be typed without their domain; the field shows it and ENZONA gets the full account. */
val ServiceKind.domain: String? get() = if (this == ServiceKind.Nauta || this == ServiceKind.NautaHome) "@nauta.com.cu" else null

/**
 * One form for every service: saved references on top, the reference, what ENZONA answers for it (bills, the phone bill or the Nauta
 * account), the amount and the card. Changing the reference asks again. A saved reference can be named or removed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ServiceFormScreen(state: ServiceFormUi, actions: ServiceFormActions, modifier: Modifier = Modifier) {
    val lookup = state.lookup
    val answered = lookup is ServiceLookupUi.Bills || lookup is ServiceLookupUi.PhoneBill || lookup is ServiceLookupUi.TopUp
    Scaffold(
        modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            NeoTopBar(state.kind.label, onBack = actions::back, actions = {
                if (state.saved) NeoIconButton(Ds.drawable.ic_edit, "Editar guardado", actions::editSaved)
            })
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = NeoSpace.gutter).padding(top = NeoSpace.s, bottom = NeoSpace.l),
                verticalArrangement = Arrangement.spacedBy(NeoSpace.l),
            ) {
                if (state.references.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(NeoSpace.s)) {
                    NeoChip("Nueva", state.selectedReference == null, { actions.pick(null) })
                    state.references.forEach { reference ->
                        NeoChip(reference.name ?: reference.value, state.selectedReference == reference.value, { actions.pick(reference) })
                    }
                }
                NeoTextField(
                    state.reference, actions::reference, state.kind.referenceLabel,
                    keyboardType = when (state.kind) {
                        ServiceKind.Electricity, ServiceKind.Landline -> KeyboardType.Number
                        else -> KeyboardType.Email
                    },
                    imeAction = ImeAction.Search,
                    error = state.referenceError,
                    supporting = owner(lookup)?.let { "Titular: $it" },
                    suffix = state.kind.domain?.takeIf { '@' !in state.reference },
                )
                when (lookup) {
                    ServiceLookupUi.None -> Unit
                    ServiceLookupUi.Loading -> LookupSkeleton()
                    is ServiceLookupUi.Failed -> Problem(lookup.message)
                    is ServiceLookupUi.Bills -> Bills(lookup, actions)
                    is ServiceLookupUi.PhoneBill -> {
                        Column(Modifier.fillMaxWidth().padding(vertical = NeoSpace.m), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Factura", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            MoneyText(lookup.due.amount, lookup.due.currency, style = MaterialTheme.typography.displaySmall, hero = true)
                        }
                    }
                    is ServiceLookupUi.TopUp -> {
                        lookup.debt?.let { Problem("Deuda pendiente: ${formatMoney(it.amount, it.currency)}. La recarga no la paga.") }
                        NeoAmountField(state.amount, actions::amount, state.currency, error = state.amountError, supporting = range(lookup))
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
    state.edit?.let { edit ->
        if (edit.removing) NeoConfirmSheet(
            title = "¿Quitar de Guardados?",
            text = "Vuelve a aparecer si lo pagas otra vez.",
            confirm = "Quitar",
            dismiss = "Cancelar",
            onConfirm = actions::confirmRemoveSaved,
            onDismiss = actions::cancelRemoveSaved,
            destructive = true,
        ) else SavedSheet(edit, actions)
    }
}

/** A saved reference: the name it shows in Guardados, or none, and the way out of Guardados. */
@Composable
private fun SavedSheet(edit: SavedEditUi, actions: ServiceFormActions) {
    NeoBottomSheet(onDismissRequest = actions::closeSaved, title = "Editar guardado") {
        Column(Modifier.padding(horizontal = NeoSpace.gutter), verticalArrangement = Arrangement.spacedBy(NeoSpace.l)) {
            NeoTextField(edit.name, actions::savedName, "Nombre (opcional)", imeAction = ImeAction.Done)
            NeoButton("Guardar", actions::saveSaved)
        }
        Column(Modifier.padding(horizontal = NeoSpace.xs).padding(top = NeoSpace.s)) {
            NeoRow(
                "Quitar de Guardados",
                leading = { IconAvatar(Ds.drawable.ic_delete, tint = Neo.colors.danger) },
                titleColor = Neo.colors.danger,
                onClick = actions::removeSaved,
            )
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

private fun owner(lookup: ServiceLookupUi): String? = when (lookup) {
    is ServiceLookupUi.Bills -> lookup.owner
    is ServiceLookupUi.PhoneBill -> lookup.owner
    is ServiceLookupUi.TopUp -> lookup.owner
    else -> null
}

@Composable
private fun Bills(lookup: ServiceLookupUi.Bills, actions: ServiceFormActions) {
    if (lookup.bills.isEmpty()) {
        Problem("No hay facturas para este código")
        return
    }
    SectionHeader("Facturas")
    NeoGroup {
        lookup.bills.forEachIndexed { index, bill ->
            if (index > 0) GroupDivider(inset = NeoSpace.l)
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

