package dev.duardo.neotransfer.plus.app

import dev.duardo.neotransfer.plus.app.routes.cardField
import dev.duardo.neotransfer.plus.app.routes.cardFromQr
import dev.duardo.neotransfer.plus.app.routes.luhn
import dev.duardo.neotransfer.plus.app.routes.message
import dev.duardo.neotransfer.plus.app.routes.notAdded
import dev.duardo.neotransfer.plus.app.routes.typeAmount
import dev.duardo.neotransfer.plus.app.routes.withCard
import dev.duardo.neotransfer.plus.app.routes.withoutCard
import dev.duardo.neotransfer.plus.core.payment.EnzonaIntentState
import dev.duardo.neotransfer.plus.core.read.EnzonaCredits
import dev.duardo.neotransfer.plus.core.read.EnzonaReadJson
import dev.duardo.neotransfer.plus.core.read.RemoteDates
import dev.duardo.neotransfer.plus.core.service.EnzonaServiceFault
import dev.duardo.neotransfer.plus.data.EnzonaFailure
import dev.duardo.neotransfer.plus.data.EnzonaResult
import dev.duardo.neotransfer.plus.data.EnzonaWalletCard
import dev.duardo.neotransfer.plus.data.contacts.Contact
import dev.duardo.neotransfer.plus.data.contacts.ContactCard
import dev.duardo.neotransfer.plus.data.enzona.EnzonaDestination
import dev.duardo.neotransfer.plus.data.enzona.EnzonaHistoryMatch
import dev.duardo.neotransfer.plus.data.enzona.EnzonaIntent
import dev.duardo.neotransfer.plus.data.enzona.EnzonaIntentType
import dev.duardo.neotransfer.plus.data.enzona.EnzonaJournalEntry
import dev.duardo.neotransfer.plus.data.enzona.EnzonaReconciliation
import dev.duardo.neotransfer.plus.data.enzona.EnzonaPaymentNotice
import dev.duardo.neotransfer.plus.data.enzona.EnzonaSessionFailure
import dev.duardo.neotransfer.plus.designsystem.component.KeypadKey
import dev.duardo.neotransfer.plus.designsystem.format.formatPhone
import dev.duardo.neotransfer.plus.designsystem.format.phoneSplit
import dev.duardo.neotransfer.plus.ui.home.label
import dev.duardo.neotransfer.plus.ui.login.message
import dev.duardo.neotransfer.plus.ui.model.AddCardUi
import dev.duardo.neotransfer.plus.ui.model.CardArt
import dev.duardo.neotransfer.plus.ui.model.CardUi
import dev.duardo.neotransfer.plus.ui.model.ContactUi
import dev.duardo.neotransfer.plus.ui.model.CounterpartUi
import dev.duardo.neotransfer.plus.ui.model.ForeignCardUi
import dev.duardo.neotransfer.plus.ui.model.LastMovementUi
import dev.duardo.neotransfer.plus.ui.model.LoginProblem
import dev.duardo.neotransfer.plus.ui.model.Money
import dev.duardo.neotransfer.plus.ui.model.NotAddedKind
import dev.duardo.neotransfer.plus.ui.model.OperationKind
import dev.duardo.neotransfer.plus.ui.model.OperationStatus
import dev.duardo.neotransfer.plus.ui.model.ReadProblem
import dev.duardo.neotransfer.plus.ui.model.RefreshState
import dev.duardo.neotransfer.plus.ui.model.ServiceKind
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test fun onlyAFailedConnectionReadsAsNoConnection() {
        val labels = EnzonaFailure.entries.associateWith { it.readProblem().label }
        assertEquals(listOf(EnzonaFailure.NETWORK), labels.filterValues { it == "Sin conexión" }.keys.toList())
        assertEquals("ENZONA tardó demasiado en responder", labels[EnzonaFailure.TIMEOUT])
        assertEquals("Error de ENZONA", labels[EnzonaFailure.REJECTED])
        assertEquals("Respuesta inesperada", labels[EnzonaFailure.INVALID_RESPONSE])
        assertEquals("Sesión caducada", labels[EnzonaFailure.SIGNED_OUT])
        assertEquals("Error en el teléfono", labels[EnzonaFailure.LOCAL])
        // A payment stopped before sending names its cause, and every case says no money moved.
        val notSent = (EnzonaSessionFailure.entries + null).associateWith(::notSentText)
        assertEquals(listOf(EnzonaSessionFailure.UNAVAILABLE), notSent.filterValues { "conexión" in it }.keys.toList())
        assertEquals("ENZONA tardó demasiado en responder. No se movió dinero.", notSent[EnzonaSessionFailure.TIMEOUT])
        assertTrue(notSent.values.all { "No se movió dinero" in it || "antes del envío" in it })
        // The same timeout reads the same under the balance and at the entrance.
        assertEquals(LoginProblem.Timeout.message, ReadProblem.Timeout.label + ".")
    }

    @Test fun aCardNotAddedNamesWhoRefusedAndCopiesItsReason() {
        val coordinates = EnzonaServiceFault("1006", null, "x", "coordenadas incorrectas")
        assertEquals(AddCardUi.NotAdded(NotAddedKind.Refused, "Coordenadas incorrectas."), EnzonaResult.Failed(EnzonaFailure.REJECTED, coordinates).notAdded())
        // Only without ENZONA's reason, a generic one.
        assertEquals(AddCardUi.NotAdded(NotAddedKind.Refused, "ENZONA no dio el motivo."), EnzonaResult.Failed(EnzonaFailure.REJECTED).notAdded())
        assertEquals("ENZONA no dio el motivo.", EnzonaResult.Failed(EnzonaFailure.REJECTED, coordinates.copy(description = " ")).notAdded().reason)
        // Without ENZONA's answer the card may be there: the same timeout words, then where to look.
        val timeout = EnzonaResult.Failed(EnzonaFailure.TIMEOUT).notAdded()
        assertEquals(NotAddedKind.Unconfirmed, timeout.kind)
        assertTrue(timeout.reason.startsWith(EnzonaFailure.TIMEOUT.message()))
        assertEquals(NotAddedKind.Unconfirmed, EnzonaResult.Failed(EnzonaFailure.NETWORK).notAdded().kind)
        assertEquals(AddCardUi.NotAdded(NotAddedKind.Failed, EnzonaFailure.LOCAL.message()), EnzonaResult.Failed(EnzonaFailure.LOCAL).notAdded())
    }

    @Test fun banksAreNamedByWhatEnzonaSaysOrByTheNumber() {
        assertEquals("BPA", Banks.shortName("Banco Popular de Ahorro", null, null))
        assertEquals("BANDEC", Banks.shortName(null, "02", null))
        assertEquals("Metropolitano", Banks.shortName("Banco Metropolitano", null, null))
        assertEquals("BANDEC", Banks.shortName(null, null, "9204069987654325"))
        assertEquals("Banco Nuevo", Banks.shortName("Banco Nuevo", "99", null))
        assertEquals(CardArt.BANMET, CardArt.of("Metropolitano"))
        assertEquals(CardArt.Neutral, CardArt.of("Banco Nuevo"))
        assertNull(Banks.ofPan("9999000011112222"))
    }

    @Test fun someoneElsesCardTellsItsBankAndCurrencyByItsNumber() {
        assertEquals(ForeignCardUi("x", "9204069901447185", "BANDEC", "Salario", "CUP"), Banks.foreignCard("x", "9204069901447185", "Salario"))
        assertEquals("BANDEC\u00A0·\u00A0USD", Banks.foreignCard("y", "9225061122334453").issuer)
        assertEquals("Metropolitano\u00A0·\u00A0CUP", Banks.issuerOf("9205950003124476"))
        assertEquals(CardArt.BANMET, Banks.foreignCard("z", "9205950003124476").art)
        assertEquals("USD", Banks.issuerOf("9760030000000000"))
        assertNull(Banks.issuerOf("4111111111111111"))
        val journal = listOf(entry("00000000-0000-4000-8000-000000000010", EnzonaIntentType.CARD_TRANSFER, "9204129900000023", EnzonaIntentState.UNKNOWN, 1))
        assertEquals(CounterpartUi.Card("9204129900000023", "BPA\u00A0·\u00A0CUP"), journal.single().toUi(listOf(bpa), emptyList()).counterpart)
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

    @Test fun noticesGroupThousandsWithAnUnbreakableSpace() {
        fun notice(amount: String) = EnzonaCredits.incoming(
            tx("""{"transaction_uuid":"t9","amount":"$amount","currency":"CUP","status_denom":"Aceptada",
                "source":{"funding_source_username":"luis","funding_source_name":"Luis"},
                "recipient":{"funding_recipient_username":"me","funding_recipient_last4":"6781"}}"""),
            "me",
        )!!.noticeTitle()
        assertEquals("Luis te envió 12\u00A0480 CUP", notice("12480.00"))
        assertEquals("Luis te envió 1\u00A0250.50 CUP", notice("1250.5"))
        assertEquals("Luis te envió 300 CUP", notice("300"))
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

    @Test fun aTransferSentFromHereIsToldByWhatThePhoneSentNotAsAService() {
        // ENZONA's row of a card transfer: "not applicable" markers as false, no recipient.
        val history = listOf(tx("""{"transaction_uuid":"r9","amount":"10","client_id":false,"invoice":false,"transaction_denom":"Transferencia","status_denom":"Aceptada"}"""))
        val juan = Contact("j", "Juan", listOf(ContactCard("jc", "9205990144718265", null)), emptyList())
        val sent = entry("00000000-0000-4000-8000-000000000009", EnzonaIntentType.CARD_TRANSFER, "9205990144718265", EnzonaIntentState.CONFIRMED, 1_000, remoteId = "r9")
        val row = accountActivity(history, listOf(sent), "me", listOf(bpa), listOf(juan), havana, withPending = false).single()
        assertEquals(OperationKind.CardTransfer, row.kind)
        assertEquals("Juan", row.title)
        assertEquals("j", row.contactId)
        assertEquals(OperationStatus.Confirmed, row.status)
        assertEquals(OperationKind.CardTransfer, history.single().toUi("me", emptyList(), havana).kind)
    }

    @Test fun aPaymentConfirmedByItsReplyFindsItsRowByAmountAndTime() {
        // No uuid ties them, and ENZONA writes `false` where it has no recipient name.
        val history = listOf(tx("""{"transaction_uuid":"r10","amount":"10","transaction_denom":"Transferencia","status_denom":"Aceptada",
            "transaction_created_at":"2026-10-15 10:02:00","recipient":{"funding_recipient_name":false,"funding_recipient_lastname":false}}"""))
        val sentAt = java.time.LocalDateTime.of(2026, 10, 15, 10, 0).atZone(havana).toInstant().toEpochMilli()
        val juan = Contact("j", "Juan", listOf(ContactCard("jc", "9205990144718265", null)), emptyList())
        fun sent(amount: String) = entry("00000000-0000-4000-8000-000000000010", EnzonaIntentType.CARD_TRANSFER, "9205990144718265",
            EnzonaIntentState.CONFIRMED, sentAt).let { it.copy(intent = it.intent.copy(amount = amount)) }
        assertEquals("Juan", accountActivity(history, listOf(sent("10")), "me", listOf(bpa), listOf(juan), havana).single().title)
        val other = accountActivity(history, listOf(sent("11")), "me", listOf(bpa), listOf(juan), havana).single()
        assertEquals("Transferencia", other.title)
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
        assertEquals(1, inProgress(journal, setOf("r1"), listOf(bpa), listOf(juan), 3_000).size)
    }

    @Test fun aPaymentThatNeverLeftThePhoneSaysSo() {
        val prepared = entry("00000000-0000-4000-8000-000000000004", EnzonaIntentType.QR_MERCHANT_PAYMENT, "07code", EnzonaIntentState.PREPARED, 1)
        val ui = prepared.toUi(listOf(bpa), emptyList(), title = "Cafetería", notSent = "No se envió.")
        assertEquals(OperationStatus.NotSent, ui.status)
        assertEquals("Cafetería", ui.title)
        assertEquals("No se envió.", ui.reason)
        val expired = entry("00000000-0000-4000-8000-000000000005", EnzonaIntentType.CARD_TRANSFER, "9205990144718265", EnzonaIntentState.NOT_DELIVERED, 1)
            .toUi(listOf(bpa), emptyList(), EnzonaPaymentNotice.NOT_SENT_AUTHORIZATION_EXPIRED)
        assertEquals(OperationStatus.NotSent, expired.status)
        assertTrue(expired.reason!!.startsWith("No se envió"))
    }

    @Test fun eachUnconfirmedCaseSaysWhatHappenedAndAnUndeliveredOneThatNoMoneyMoved() {
        val base = entry("00000000-0000-4000-8000-000000000006", EnzonaIntentType.CARD_TRANSFER, "9205990144718265", EnzonaIntentState.UNKNOWN, 1)
        fun ui(entry: EnzonaJournalEntry) = entry.toUi(listOf(bpa), emptyList())
        assertEquals("Sin respuesta de ENZONA. Revisa la actividad antes de repetirla.", ui(base.copy(httpStatus = 0)).reason)
        assertEquals("ENZONA respondió: “Contraseña de pago incorrecta”. Revisa la actividad antes de repetirla.",
            ui(base.copy(httpStatus = 400, faultText = "Contraseña de pago incorrecta.")).reason)
        assertEquals(UnconfirmedReason, ui(base.copy(httpStatus = 500)).reason)
        assertEquals(OperationStatus.Unconfirmed, ui(base.copy(httpStatus = 0)).status)
        val undelivered = ui(base.copy(state = EnzonaIntentState.NOT_DELIVERED))
        assertEquals(OperationStatus.NotSent, undelivered.status)
        assertEquals("No llegó a ENZONA. No se movió dinero.", undelivered.reason)
        assertEquals("ENZONA respondió: “Saldo insuficiente”.", ui(base.copy(state = EnzonaIntentState.REJECTED, faultText = "Saldo insuficiente")).reason)
    }

    @Test fun aQuarterHourWithoutItsRowInTheHistoryMeansNotDone() {
        val wait = EnzonaReconciliation.NOT_DONE_AFTER
        val base = entry("00000000-0000-4000-8000-000000000007", EnzonaIntentType.CARD_TRANSFER, "9205990144718265", EnzonaIntentState.UNKNOWN, 1_000)
        fun ui(entry: EnzonaJournalEntry) = entry.toUi(listOf(bpa), emptyList())
        assertEquals(OperationStatus.Unconfirmed, ui(base.copy(notFoundAt = 1_000 + wait - 1)).status)
        val missing = base.copy(notFoundAt = 1_000 + wait)
        assertEquals(OperationStatus.NotDone, ui(missing).status)
        assertEquals("ENZONA no la registró. No se movió dinero.", ui(missing).reason)
        val marked = base.copy(markedNotDoneAt = 2_000)
        assertEquals(OperationStatus.NotDone, ui(marked).status)
        // Off Home, still in Activity, and never tied to a contact as money sent.
        assertTrue(inProgress(listOf(missing, marked), emptySet(), listOf(bpa), emptyList(), 3_000 + wait).isEmpty())
        assertEquals(OperationStatus.NotDone, accountActivity(emptyList(), listOf(marked), "me", listOf(bpa), emptyList(), havana).single().status)
        val juan = Contact("j", "Juan", listOf(ContactCard("jc", "9205990144718265", null)), emptyList())
        assertTrue(contactMovements(listOf(marked), emptyList(), "me", listOf(bpa), listOf(juan), havana).isEmpty())
        val byAmount = ui(base.copy(state = EnzonaIntentState.CONFIRMED, remoteId = "r", matchedBy = EnzonaHistoryMatch.AMOUNT_AND_TIME))
        assertEquals(ByAmountReason, byAmount.reason)
    }

    @Test fun aPasswordIsKeptOnlyOnceEnzonaConfirmedAndDoubtedWhenEnzonaAnsweredOtherwise() {
        assertEquals(PasswordOutcome.Confirmed, passwordOutcome(EnzonaIntentState.CONFIRMED, replied = true))
        assertEquals(PasswordOutcome.InDoubt, passwordOutcome(EnzonaIntentState.UNKNOWN, replied = true))
        assertEquals(PasswordOutcome.InDoubt, passwordOutcome(EnzonaIntentState.REJECTED, replied = true))
        assertEquals(PasswordOutcome.Wait, passwordOutcome(EnzonaIntentState.UNKNOWN, replied = false))
        assertEquals(PasswordOutcome.Unused, passwordOutcome(EnzonaIntentState.NOT_DELIVERED, replied = false))
        assertEquals(PasswordOutcome.Unused, passwordOutcome(EnzonaIntentState.PREPARED, replied = false))
    }

    @Test fun onlyCardTransfersSentFromThisPhoneAreTiedToAContact() {
        val juanPan = "9204069901447185"
        val shared = "9204123331880422"
        val juan = Contact("phone:k-juan", "Juan", listOf(ContactCard("j1", juanPan, null), ContactCard("j2", shared, null)), listOf("53124578"))
        val beto = Contact("b", "Beto", listOf(ContactCard("b1", "9205950003124476", null), ContactCard("b2", shared, null)), emptyList())
        // ENZONA's own row for the first one: its name and four digits only.
        val history = listOf(tx("""{"transaction_uuid":"r1","amount":"300","status_denom":"Aceptada","transaction_created_at":"2026-10-15 10:00:00",
            "recipient":{"funding_recipient_name":"Juan Carlos","funding_recipient_lastname":"Pérez","funding_recipient_last4":"7185"}}"""))
        val journal = listOf(
            entry("00000000-0000-4000-8000-000000000011", EnzonaIntentType.CARD_TRANSFER, juanPan, EnzonaIntentState.CONFIRMED, 1_000, remoteId = "r1"),
            entry("00000000-0000-4000-8000-000000000012", EnzonaIntentType.CARD_TRANSFER, juanPan, EnzonaIntentState.UNKNOWN, 2_000),
            entry("00000000-0000-4000-8000-000000000013", EnzonaIntentType.CARD_TRANSFER, juanPan, EnzonaIntentState.REJECTED, 3_000),
            entry("00000000-0000-4000-8000-000000000014", EnzonaIntentType.CARD_TRANSFER, juanPan, EnzonaIntentState.PREPARED, 4_000),
            entry("00000000-0000-4000-8000-000000000015", EnzonaIntentType.CARD_TRANSFER, shared, EnzonaIntentState.CONFIRMED, 5_000),
            entry("00000000-0000-4000-8000-000000000016", EnzonaIntentType.CARD_TRANSFER, "9205950003124476", EnzonaIntentState.CONFIRMED, 6_000),
            entry("00000000-0000-4000-8000-000000000017", EnzonaIntentType.ACCOUNT_TRANSFER, "acc-uuid", EnzonaIntentState.CONFIRMED, 7_000, username = "juan"),
        )
        val tied = contactMovements(journal, history, "me", listOf(bpa), listOf(juan, beto), havana)
        assertEquals(setOf("phone:k-juan", "b"), tied.keys)
        assertEquals(listOf("j:00000000-0000-4000-8000-000000000012", "tx:r1"), tied.getValue("phone:k-juan").map { it.id })
        assertEquals("Juan Carlos Pérez", tied.getValue("phone:k-juan").last().title)
        assertTrue(tied.values.flatten().all { it.contactId != null })
        assertEquals(listOf("j:00000000-0000-4000-8000-000000000016"), tied.getValue("b").map { it.id })
        // Nothing from ENZONA's history alone: the same name or last four digits never tie a movement.
        assertTrue(contactMovements(emptyList(), history, "me", listOf(bpa), listOf(juan), havana).isEmpty())
    }

    @Test fun theContactsTabListsWhoMoneyWentToNewestFirstThenSavedCardsByName() {
        val card = ForeignCardUi("c", "9204069901447185")
        fun person(name: String, minutesAgo: Long?, cards: List<ForeignCardUi> = emptyList()) =
            ContactUi(name, name, cards, emptyList(), last = minutesAgo?.let { LastMovementUi(Money(BigDecimal.TEN, "CUP"), Instant.ofEpochSecond(1_000_000 - it * 60)) })
        val people = listOf(person("Zoe", null, listOf(card)), person("Ana", 30), person("Óscar", 5), person("Álvaro", null, listOf(card)), person("Nadie", null))
        assertEquals(listOf("Óscar", "Ana", "Álvaro", "Zoe"), people.people().map { it.name })
    }

    @Test fun phonesSplitAsAndroidsContactsWritesThem() {
        assertEquals("5\u00A02854627", formatPhone("52854627"))
        assertEquals("6\u00A03123456", formatPhone("63123456"))
        assertEquals("7\u00A08302214", formatPhone("78302214"))
        assertEquals("32\u00A0291234", formatPhone("32291234"))
        assertEquals("21\u00A0345678", formatPhone("21345678"))
        assertEquals("45\u00A0123456", formatPhone("45123456"))
        assertEquals("25123456", formatPhone("25123456"))
        assertEquals("8001234", formatPhone("8001234"))
        assertEquals("5352854627", formatPhone("+53 5285 4627"))
        // While typed, the field splits where the finished number will.
        assertEquals(listOf(0, 1, 1, 2, 0, 0), listOf("", "5", "7830", "32", "2", "9").map(::phoneSplit))
    }

    @Test fun aCardFieldTellsTheBankAndRefusesABadOrRepeatedNumber() {
        val typing = cardField("920406", "")
        assertEquals("BANDEC", typing.bank)
        assertEquals("CUP", typing.currency)
        assertNull(typing.error)
        assertEquals("Algún dígito está mal", cardField("9204067704229017", "").error)
        assertEquals("Ya está guardada", cardField("9204067704229016", "", taken = listOf("9204067704229016")).error)
        assertNull(cardField("9204067704229016", "Salario").error)
        assertEquals("Metropolitano\u00A0·\u00A0CUP", cardField("9205950003124476", "Salario").card.issuer)
    }

    @Test fun aContactsCardsAreAddedRenamedAndRemovedInPlace() {
        val juan = Contact("phone:k-juan", "Juan", listOf(ContactCard("j1", "9204069901447185", null)), listOf("53124578"))
        val added = juan.withCard(null, "9225061122334453", "")
        assertEquals(listOf("9204069901447185", "9225061122334453"), added.cards.map { it.pan })
        val dollars = added.cards.last().id
        val named = added.withCard(dollars, "9225061122334453", "  Dólares ")
        assertEquals(listOf(null, "Dólares"), named.cards.map { it.label })
        assertEquals(added.cards.map { it.id }, named.cards.map { it.id })
        assertEquals(listOf("j1"), named.withoutCard(dollars).cards.map { it.id })
        assertEquals(named, named.withoutCard("missing"))
    }

    @Test fun recentRecipientsAreNamedOnlyByAnUnambiguousContact() {
        val pan = "9205990144718265"
        val journal = listOf(entry("00000000-0000-4000-8000-000000000006", EnzonaIntentType.CARD_TRANSFER, pan, EnzonaIntentState.CONFIRMED, 1))
        val one = listOf(Contact("a", "Ana", listOf(ContactCard("x", pan, null)), emptyList()))
        assertEquals("Ana", recentRecipients(journal, one).single().name)
        val two = one + Contact("b", "Beto", listOf(ContactCard("y", pan, null)), emptyList())
        assertNull(recentRecipients(journal, two).single().name)
    }

    @Test fun whatEnzonaConfirmedIsSavedWithItsNameUntilTakenOut() {
        val electricity = entry("00000000-0000-4000-8000-000000000007", EnzonaIntentType.INVOICE_PAYMENT, "01234567891", EnzonaIntentState.CONFIRMED, 1, service = "ELECTRICITY")
        val unconfirmed = entry("00000000-0000-4000-8000-000000000008", EnzonaIntentType.INVOICE_PAYMENT, "78302214", EnzonaIntentState.UNKNOWN, 2, service = "FIXED_PHONE")
        val nauta = entry("00000000-0000-4000-8000-000000000009", EnzonaIntentType.INVOICE_PAYMENT, "ana@nauta.com.cu", EnzonaIntentState.CONFIRMED, 3, service = "NAUTA")
        val key = referenceKey(ServiceKind.Electricity, "01234567891")
        assertEquals("ELECTRICITY:01234567891", key)
        // Only what ENZONA confirmed, by service: electricity before Nauta, the unconfirmed phone bill left out.
        val saved = savedReferences(listOf(nauta, unconfirmed, electricity), mapOf(key to "Casa"), emptyMap())
        assertEquals(listOf(ServiceKind.Electricity, ServiceKind.Nauta), saved.map { it.kind })
        assertEquals("Casa", saved.first().name)
        assertEquals(BigDecimal("300"), saved.first().lastPaid?.amount)
        assertNull(saved.last().name)
        // Taken out, it stays out until a payment made after that.
        assertEquals(listOf(ServiceKind.Nauta), savedReferences(listOf(electricity, nauta), emptyMap(), mapOf(key to 1L)).map { it.kind })
        val again = electricity.copy(intent = electricity.intent.copy(id = "00000000-0000-4000-8000-000000000010", preparedAt = 5))
        assertEquals(2, savedReferences(listOf(electricity, again, nauta), emptyMap(), mapOf(key to 1L)).size)
    }

    @Test fun aPaymentThatNeverReachedEnzonaWaitsOnHomeUntilSentAgain() {
        val hour = 60 * 60_000L
        val lost = entry("00000000-0000-4000-8000-000000000011", EnzonaIntentType.CARD_TRANSFER, "9205990144718265", EnzonaIntentState.NOT_DELIVERED, 1_000)
        val pending = inProgress(listOf(lost), emptySet(), listOf(bpa), emptyList(), 1_000 + hour).single()
        assertEquals(OperationStatus.NotSent, pending.status)
        // Twelve hours later it is no longer offered.
        assertTrue(inProgress(listOf(lost), emptySet(), listOf(bpa), emptyList(), 1_000 + RetryWindowMillis).isEmpty())
        // Sent again: the new attempt takes its place; one prepared and never sent does not.
        val prepared = lost.copy(intent = lost.intent.copy(id = "00000000-0000-4000-8000-000000000012", preparedAt = 2_000), state = EnzonaIntentState.PREPARED)
        assertEquals(listOf(lost.intent.id), inProgress(listOf(lost, prepared), emptySet(), listOf(bpa), emptyList(), 3_000).map { it.id.removePrefix("j:") })
        val sent = prepared.copy(state = EnzonaIntentState.SUBMITTING)
        assertEquals(listOf(sent.intent.id), inProgress(listOf(lost, sent), emptySet(), listOf(bpa), emptyList(), 3_000).map { it.id.removePrefix("j:") })
    }

    @Test fun theSameTransferStillUnconfirmedAsksBeforeGoingAgain() {
        val pan = "9205990144718265"
        val unconfirmed = entry("00000000-0000-4000-8000-000000000013", EnzonaIntentType.CARD_TRANSFER, pan, EnzonaIntentState.UNKNOWN, 1_000)
        assertEquals(unconfirmed, pendingTransfer(listOf(unconfirmed), emptySet(), "c1", pan, BigDecimal("300.00"), "cup"))
        // Another amount, another card, already in the history, or marked as not done: nothing to ask.
        assertNull(pendingTransfer(listOf(unconfirmed), emptySet(), "c1", pan, BigDecimal("301"), "CUP"))
        assertNull(pendingTransfer(listOf(unconfirmed), emptySet(), "c2", pan, BigDecimal("300"), "CUP"))
        assertNull(pendingTransfer(listOf(unconfirmed.copy(remoteId = "r1")), setOf("r1"), "c1", pan, BigDecimal("300"), "CUP"))
        assertNull(pendingTransfer(listOf(unconfirmed.copy(markedNotDoneAt = 2_000)), emptySet(), "c1", pan, BigDecimal("300"), "CUP"))
        assertNull(pendingTransfer(listOf(unconfirmed.copy(state = EnzonaIntentState.CONFIRMED)), emptySet(), "c1", pan, BigDecimal("300"), "CUP"))
    }

    @Test fun transfermovilCardCodesAndBareNumbersCarryTheirCard() {
        assertEquals("9212060123456781", cardFromQr("TRANSFERMOVIL_ETECSA,TRANSFERENCIA,9212060123456781,53124578,"))
        assertEquals("9212060123456781", cardFromQr("transfermovil_etecsa;transferencia; 9212 0601 2345 6781 ;"))
        assertEquals("9212060123456781", cardFromQr("9212-0601-2345-6781"))
        assertNull(cardFromQr("TRANSFERMOVIL_ETECSA,TRANSFERENCIA,9212060123456782,"))
        assertNull(cardFromQr("07CLE00417PAGO"))
        assertNull(cardFromQr("{\"id_transaccion\":\"1\"}"))
    }

    @Test fun keypadAndCardNumbersRefuseWhatCannotBeSent() {
        assertEquals("0.", typeAmount("", KeypadKey.Decimal))
        assertEquals("12.5", typeAmount("12.", KeypadKey.Digit(5)))
        assertEquals("12.55", typeAmount("12.55", KeypadKey.Digit(1)))
        assertEquals("7", typeAmount("0", KeypadKey.Digit(7)))
        assertTrue(luhn("9205990144718265"))
    }

    @Test fun aBalanceReadBeforeAnotherStepFailedStaysUpdated() {
        val started = Instant.parse("2026-10-19T12:00:00Z")
        val failed = RefreshState.Failed(ReadProblem.Offline, started)
        // The refresh read this balance, then the history failed: the balance is fresh.
        assertNull(failed.problemFor(started.plusSeconds(2)))
        // The balance is older than the failed refresh: it was not read.
        assertEquals(ReadProblem.Offline, failed.problemFor(started.minusSeconds(600)))
    }

    @Test fun theDefaultCardIsTheOneEnzonaMarksAsPrimarySource() {
        fun wallet(id: String, primary: String) = EnzonaWalletCard(
            EnzonaReadJson.cards("""{"card":[{"funding_source_uuid":"$id","last4":"6781","currency":"CUP","bank_name":"BPA","primary_source":$primary}]}""").single(),
            null, null, null, null,
        )
        for (primary in listOf("true", "1", "\"1\"", "\"TRUE\"")) assertTrue(wallet("c", primary).primary, primary)
        for (primary in listOf("false", "0", "\"0\"", "null")) assertTrue(!wallet("c", primary).primary, primary)
        val cards = accountCardsUi(listOf(wallet("a", "false"), wallet("b", "true")), busy = setOf("a"))
        assertEquals(listOf("a" to false, "b" to true), cards.map { it.card.id to it.primary })
        assertEquals(listOf(true, false), cards.map { it.busy })
    }
}
