package dev.duardo.neotransfer.plus.preview

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.duardo.neotransfer.plus.designsystem.format.formatCardNumber
import dev.duardo.neotransfer.plus.ui.contacts.ContactActions
import dev.duardo.neotransfer.plus.ui.contacts.ContactDetailScreen
import dev.duardo.neotransfer.plus.ui.model.Appearance
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A contact's cards are managed on their page: each card's menu copies, edits or removes it; the last row adds one. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xhdpi")
class ContactCardsTest {
    @get:Rule
    val compose = createComposeRule()

    private val calls = mutableListOf<String>()
    /** Records each action with its arguments; the object methods Compose may call answer as identity. */
    private val actions: ContactActions = Proxy.newProxyInstance(ContactActions::class.java.classLoader, arrayOf(ContactActions::class.java)) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "ContactActions"
            else -> {
                calls += (listOf(method.name) + args.orEmpty().map { it.toString() }).joinToString(" ")
                null
            }
        }
    } as ContactActions

    @Test fun eachCardHasItsMenuAndTheGroupEndsInAddingOne() {
        compose.setContent { PreviewHost(Appearance.Light) { ContactDetailScreen(PreviewData.carmen, actions) } }
        val menus = compose.onAllNodesWithContentDescription("Opciones de la tarjeta")
        assertEquals(2, menus.fetchSemanticsNodes().size)

        menus[1].performClick()
        compose.onNodeWithText("Copiar número").assertIsDisplayed()
        compose.onNodeWithText("Editar").performClick()
        menus[1].performClick()
        compose.onNodeWithText("Quitar").performClick()
        menus[0].performClick()
        compose.onNodeWithText("Copiar número").performClick()
        compose.onNodeWithText("Añadir tarjeta").performClick()
        compose.onNodeWithText(formatCardNumber("9205950003124476")).performClick()

        assertEquals(listOf("editCard ca-2", "removeCard ca-2", "copy 9205950003124476", "addCard", "transfer ca-1"), calls)
    }

    @Test fun withoutCardsTheMainActionAddsOne() {
        compose.setContent { PreviewHost(Appearance.Light) { ContactDetailScreen(PreviewData.rafael, actions) } }
        compose.onNodeWithText("Añadir tarjeta").performClick()
        assertEquals(listOf("addCard"), calls)
    }
}
