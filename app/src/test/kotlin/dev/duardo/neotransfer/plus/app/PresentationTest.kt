package dev.duardo.neotransfer.plus.app

import dev.duardo.neotransfer.plus.app.routes.cardNumberError
import dev.duardo.neotransfer.plus.app.routes.luhn
import dev.duardo.neotransfer.plus.app.routes.typeAmount
import dev.duardo.neotransfer.plus.core.payment.EnzonaIntentState
import dev.duardo.neotransfer.plus.core.read.EnzonaCredits
import dev.duardo.neotransfer.plus.core.read.EnzonaReadJson
import dev.duardo.neotransfer.plus.data.contacts.Contact
import dev.duardo.neotransfer.plus.data.contacts.ContactCard
import dev.duardo.neotransfer.plus.data.enzona.EnzonaDestination
import dev.duardo.neotransfer.plus.data.enzona.EnzonaIntent
import dev.duardo.neotransfer.plus.data.enzona.EnzonaIntentType
import dev.duardo.neotransfer.plus.data.enzona.EnzonaJournalEntry
import dev.duardo.neotransfer.plus.data.enzona.EnzonaPaymentNotice
import dev.duardo.neotransfer.plus.designsystem.component.KeypadKey
import dev.duardo.neotransfer.plus.ui.model.CardArt
import dev.duardo.neotransfer.plus.ui.model.CardUi
import dev.duardo.neotransfer.plus.ui.model.CounterpartUi
import dev.duardo.neotransfer.plus.ui.model.OperationKind
import dev.duardo.neotransfer.plus.ui.model.OperationStatus
import dev.duardo.neotransfer.plus.ui.model.ServiceKind
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What people read on screen, from synthetic ENZONA rows. No network, no real data. */
class PresentationTest {
    private val havana = ZoneId.of("America/Havana")
    private val bpa = CardUi("c1", "BPA", CardArt.BPA, null, "6781", "CUP")

    private fun tx(json: String) = EnzonaReadJson.transaction(json)

    private fun entry(id: String, type: EnzonaIntentType, destination: String, state: EnzonaIntentState, preparedAt: Long,
        username: String? = null, service: String? = null, remoteId: String? = null) = EnzonaJournalEntry(
        EnzonaIntent(id, "owner", "c1", type, EnzonaDestination(destination, username), "300", "CUP", preparedAt = preparedAt,
            invoiceService = service, invoiceOwner = service?.let { "Ana" }),
        state, remoteId,
    )

    @Test fun banksAreNamedByWhatEnzonaSaysOrByThePrefix() {
        assertEquals("BPA", Banks.shortName("Banco Popular de Ahorro", null, null))
        assertEquals("BANDEC", Banks.shortName(null, "02", null))
        assertEquals("BANMET", Banks.shortName("Banco Metropolitano", null, null))
        assertEquals("BANDEC", Banks.shortName(null, null, "9205129987654325"))
        assertEquals("Banco Nuevo", Banks.shortName("Banco Nuevo", "99", null))
        assertEquals(CardArt.Neutral, Banks.art("BANMET"))
        assertNull(Banks.ofPan("9999000011112222"))
    }

    @Test fun datesAreReadOnlyInKnownShapesAndCubanLocalTimeWithoutOffset() {
        assertEquals(Instant.parse("2026-10-15T18:30:00Z"), RemoteDates.parse("2026-10-15 14:30:00", havana))
        assertEquals(Instant.parse("2026-10-15T14:30:00Z"), RemoteDates.parse("2026-10-15T14:30:00Z", havana))
        assertEquals(Instant.parse("2026-10-15T18:30:00Z"), RemoteDates.parse("2026-10-15T14:30:00", havana))
        assertNull(RemoteDates.parse("15/10/2026", havana))
        assertNull(RemoteDates.parse(null, havana))
    }

    @Test fun expiryIsPrintedAsMonthAndYear() {
        assertEquals("03/29", expiryText("0329"))
        assertEquals("03/29", expiryText("03/29"))
        assertEquals("03/29", expiryText("2029-03"))
        assertEquals("marzo 2029", expiryText("marzo 2029"))
    }

    @Test fun incomingCreditNamesTheSenderAsEnzonaDoesAndLandsOnTheMatchingCard() {
        val row = tx("""{"transaction_uuid":"t1","amount":"250.50","currency":"CUP","status_denom":"Aceptada",
            "transaction_created_at":"2026-10-15 09:10:00",
            "source":{"funding_source_username":"luis","funding_source_name":"Luis","funding_source_lastname":"Rey","funding_source_last4":"1111"},
            "recipient":{"funding_recipient_username":"me","funding_recipient_last4":"6781"}}""")
        val ui = row.toUi("me", listOf(bpa), havana)
        assertEquals(OperationKind.Incoming, ui.kind)
        assertEquals("Luis Rey", ui.title)
        assertEquals(OperationStatus.Confirmed, ui.status)
        assertEquals(BigDecimal("250.50"), ui.amount?.amount)
        assertEquals("c1", ui.card?.id)
        assertEquals(CounterpartUi.CardEnding("1111"), ui.counterpart)
        assertEquals("Luis Rey te envió 250.50 CUP", EnzonaCredits.incoming(row, "me")!!.noticeTitle())
    }

    @Test fun unknownStateWordsAreShownAsEnzonaWritesThem() {
        val pending = tx("""{"transaction_uuid":"t2","amount":"10","status_denom":"Pendiente","recipient":{"funding_recipient_last4":"4325"}}""")
        assertEquals(OperationStatus.Pending, pending.toUi("me", emptyList(), havana).status)
        val other = tx("""{"transaction_uuid":"t3","amount":"10","status_denom":"En revisión","recipient":{"funding_recipient_last4":"4325"}}""")
        val ui = other.toUi("me", emptyList(), havana)
        assertEquals(OperationStatus.Other, ui.status)
        assertEquals("En revisión", ui.statusLabel)
        assertEquals("•••• 4325", ui.title)
        assertEquals(OperationKind.CardTransfer, ui.kind)
    }

    @Test fun servicePaymentsAreNamedByTheirServiceAndCarryTheReference() {
        val row = tx("""{"transaction_uuid":"t4","amount":"387.10","client_id":"01234567891","transaction_denom":"Pago de factura eléctrica","status_denom":"Aceptada"}""")
        val ui = row.toUi("me", emptyList(), havana)
        assertEquals(OperationKind.Electricity, ui.kind)
        assertEquals("Electricidad", ui.title)
        assertEquals(CounterpartUi.Reference("01234567891"), ui.counterpart)
    }

    @Test fun activityShowsUnconfirmedPaymentsUntilTheHistoryHasThem() {
        val history = listOf(tx("""{"transaction_uuid":"r1","amount":"300","recipient":{"funding_recipient_last4":"8265"},"transaction_created_at":"2026-10-15 10:00:00"}"""))
        val journal = listOf(
            entry("00000000-0000-4000-8000-000000000001", EnzonaIntentType.CARD_TRANSFER, "9205990144718265", EnzonaIntentState.UNKNOWN, 1_000),
            entry("00000000-0000-4000-8000-000000000002", EnzonaIntentType.CARD_TRANSFER, "9205990144718265", EnzonaIntentState.UNKNOWN, 2_000, remoteId = "r1"),
            entry("00000000-0000-4000-8000-000000000003", EnzonaIntentType.CARD_TRANSFER, "9205990144718265", EnzonaIntentState.CONFIRMED, 3_000),
        )
        val juan = Contact("j", "Juan", listOf(ContactCard("jc", "9205990144718265", null)), emptyList())
        val rows = accountActivity(history, journal, "me", listOf(bpa), listOf(juan), havana)
        assertEquals(listOf("tx:r1", "j:00000000-0000-4000-8000-000000000001"), rows.map { it.id })
        val unconfirmed = rows.last()
        assertEquals(OperationStatus.Unconfirmed, unconfirmed.status)
        assertEquals("Juan", unconfirmed.title)
        assertEquals(UnconfirmedReason, unconfirmed.reason)
        assertEquals(1, inProgress(journal, setOf("r1"), listOf(bpa), listOf(juan)).size)
    }

    @Test fun aPaymentThatNeverLeftThePhoneSaysSo() {
        val prepared = entry("00000000-0000-4000-8000-000000000004", EnzonaIntentType.QR_MERCHANT_PAYMENT, "07code", EnzonaIntentState.PREPARED, 1)
        val ui = prepared.toUi(listOf(bpa), emptyList(), title = "Cafetería", notSent = "No se envió.")
        assertEquals(OperationStatus.NotSent, ui.status)
        assertEquals("Cafetería", ui.title)
        assertEquals("No se envió.", ui.reason)
        val expired = entry("00000000-0000-4000-8000-000000000005", EnzonaIntentType.CARD_TRANSFER, "9205990144718265", EnzonaIntentState.UNKNOWN, 1)
            .toUi(listOf(bpa), emptyList(), EnzonaPaymentNotice.NOT_SENT_AUTHORIZATION_EXPIRED)
        assertTrue(expired.reason!!.startsWith("No se envió"))
    }

    @Test fun recentRecipientsAreNamedOnlyByAnUnambiguousContact() {
        val pan = "9205990144718265"
        val journal = listOf(entry("00000000-0000-4000-8000-000000000006", EnzonaIntentType.CARD_TRANSFER, pan, EnzonaIntentState.CONFIRMED, 1))
        val one = listOf(Contact("a", "Ana", listOf(ContactCard("x", pan, null)), emptyList()))
        assertEquals("Ana", recentRecipients(journal, one).single().name)
        val two = one + Contact("b", "Beto", listOf(ContactCard("y", pan, null)), emptyList())
        assertNull(recentRecipients(journal, two).single().name)
    }

    @Test fun recentReferencesKeepTheLastConfirmedAmount() {
        val journal = listOf(
            entry("00000000-0000-4000-8000-000000000007", EnzonaIntentType.INVOICE_PAYMENT, "01234567891", EnzonaIntentState.CONFIRMED, 1, service = "ELECTRICITY"),
            entry("00000000-0000-4000-8000-000000000008", EnzonaIntentType.INVOICE_PAYMENT, "01234567891", EnzonaIntentState.UNKNOWN, 2, service = "ELECTRICITY"),
        )
        val reference = recentReferences(journal).single()
        assertEquals(ServiceKind.Electricity, reference.kind)
        assertEquals(BigDecimal("300"), reference.lastPaid?.amount)
    }

    @Test fun keypadAndCardNumbersRefuseWhatCannotBeSent() {
        assertEquals("0.", typeAmount("", KeypadKey.Decimal))
        assertEquals("12.5", typeAmount("12.", KeypadKey.Digit(5)))
        assertEquals("12.55", typeAmount("12.55", KeypadKey.Digit(1)))
        assertEquals("7", typeAmount("0", KeypadKey.Digit(7)))
        assertTrue(luhn("9205990144718265"))
        assertEquals("Número incompleto", cardNumberError("92059901"))
        assertEquals("Número no válido: revisa los dígitos", cardNumberError("9205990144718266"))
        assertNull(cardNumberError("9205990144718265"))
    }
}
