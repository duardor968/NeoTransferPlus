package dev.duardo.neotransfer.plus.app.routes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import dev.duardo.neotransfer.plus.app.PlusApp
import dev.duardo.neotransfer.plus.designsystem.component.NeoBottomSheet
import dev.duardo.neotransfer.plus.designsystem.component.NeoButton
import dev.duardo.neotransfer.plus.designsystem.component.NeoTextField
import dev.duardo.neotransfer.plus.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.plus.ui.common.numberLine
import dev.duardo.neotransfer.plus.ui.model.CardUi
import kotlinx.coroutines.launch

@Composable
internal fun CardAliasEditor(plus: PlusApp, card: CardUi, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    CardAliasSheet(card, saving, error, close) { alias ->
        if (!saving) {
            saving = true
            scope.launch {
                val failure = plus.repository.saveCardLink(card.id, null, alias, card.ownerSubject)
                saving = false
                if (failure == null) close() else error = failure.message()
            }
        }
    }
}

@Composable
internal fun CardAliasSheet(card: CardUi, saving: Boolean, error: String?, close: () -> Unit, save: (String) -> Unit) {
    var alias by rememberSaveable(card.ownerSubject, card.id) { mutableStateOf(card.alias.orEmpty()) }
    NeoBottomSheet(onDismissRequest = close, title = if (card.alias == null) "Añadir alias" else "Editar alias") {
        Column(Modifier.padding(horizontal = NeoSpace.xl), verticalArrangement = Arrangement.spacedBy(NeoSpace.m)) {
            Text(card.numberLine, style = MaterialTheme.typography.titleMedium)
            NeoTextField(alias, { if (!saving) alias = it.take(20) }, "Alias privado", imeAction = ImeAction.Done, error = error)
            NeoButton("Guardar", { save(alias.trim()) }, enabled = !saving && alias.trim() != card.alias.orEmpty(), loading = saving)
        }
    }
}
