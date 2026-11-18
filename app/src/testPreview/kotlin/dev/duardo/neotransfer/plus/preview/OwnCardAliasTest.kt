package dev.duardo.neotransfer.plus.preview

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import dev.duardo.neotransfer.plus.app.exportedActivity
import dev.duardo.neotransfer.plus.app.routes.CardAliasSheet
import dev.duardo.neotransfer.plus.app.routes.receiptText
import dev.duardo.neotransfer.plus.designsystem.format.formatCardNumber
import dev.duardo.neotransfer.plus.ui.common.SourceCardPicker
import dev.duardo.neotransfer.plus.ui.common.TimeLabels
import dev.duardo.neotransfer.plus.ui.model.Appearance
import java.time.Clock
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xhdpi")
class OwnCardAliasTest {
    @get:Rule val compose = createComposeRule()

    @Test fun repeatedNamesStillSelectTheSpecificCard() {
        val sources = PreviewData.wallet.map { it.copy(card = it.card.copy(alias = "Nómina")) }
        var selected: String? = null
        compose.setContent {
            PreviewHost(Appearance.Light) {
                SourceCardPicker(sources, 0, { selected = sources[it].card.id })
            }
        }
        compose.onNodeWithText(formatCardNumber(PreviewData.bpa.number!!)).assertIsDisplayed()
        compose.onNodeWithContentDescription("Cambiar tarjeta").performClick()
        compose.onNodeWithText("•••• 4325").performClick()
        assertEquals(PreviewData.bandec.id, selected)
    }

    @Test fun editAndRemoveUsingOnlyTheLastFour() {
        val saved = mutableListOf<String>()
        compose.setContent {
            PreviewHost(Appearance.Light) {
                CardAliasSheet(PreviewData.bandec, false, null, {}, { saved += it })
            }
        }
        compose.onNodeWithText("•••• 4325").assertIsDisplayed()
        val input = compose.onNode(hasSetTextAction())
        input.performTextClearance()
        compose.onNodeWithText("Guardar").performClick()
        input.performTextInput("Nómina")
        compose.onNodeWithText("Guardar").performClick()
        assertEquals(listOf("", "Nómina"), saved)
    }

    @Test fun reviewedNumberAndLocalAliasAreVisibleTogether() {
        compose.setContent { PreviewHost(Appearance.Light) { PreviewCatalog.content("transfer-review") } }
        compose.onNodeWithText(formatCardNumber(PreviewData.bpa.number!!)).assertIsDisplayed()
        compose.onNodeWithText("Nómina", substring = true).assertIsDisplayed()
    }

    @Test fun sharingAndPeerSyncAreIdenticalWithOrWithoutNickname() {
        val operation = PreviewData.confirmedToJuan.copy(card = PreviewData.bpa)
        val plain = operation.copy(card = PreviewData.bpa.copy(alias = null))
        val labels = TimeLabels(Clock.systemUTC())
        val text = receiptText(operation, labels)
        assertEquals(receiptText(plain, labels), text)
        assertFalse(text.contains("Nómina"))
        val now = operation.at!!.toEpochMilli()
        val shared = exportedActivity(listOf(operation), now)
        assertEquals(PreviewData.bpa.number, shared.single().card)
        assertEquals(exportedActivity(listOf(plain), now), shared)
    }
}
