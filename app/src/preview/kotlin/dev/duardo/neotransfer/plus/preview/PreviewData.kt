package dev.duardo.neotransfer.plus.preview

import dev.duardo.neotransfer.plus.app.NoReplyReason
import dev.duardo.neotransfer.plus.app.NotDeliveredReason
import dev.duardo.neotransfer.plus.ui.model.AccountCardUi
import dev.duardo.neotransfer.plus.ui.model.AccountUi
import dev.duardo.neotransfer.plus.ui.model.BalanceUi
import dev.duardo.neotransfer.plus.ui.model.CardArt
import dev.duardo.neotransfer.plus.ui.model.CardUi
import dev.duardo.neotransfer.plus.ui.model.ContactUi
import dev.duardo.neotransfer.plus.ui.model.CounterpartUi
import dev.duardo.neotransfer.plus.ui.model.ForeignCardUi
import dev.duardo.neotransfer.plus.ui.model.LastMovementUi
import dev.duardo.neotransfer.plus.ui.model.Money
import dev.duardo.neotransfer.plus.ui.model.OperationKind
import dev.duardo.neotransfer.plus.ui.model.OperationStatus
import dev.duardo.neotransfer.plus.ui.model.OperationUi
import dev.duardo.neotransfer.plus.ui.model.RecipientUi
import dev.duardo.neotransfer.plus.ui.model.SavedReferenceUi
import dev.duardo.neotransfer.plus.ui.model.ServiceKind
import dev.duardo.neotransfer.plus.ui.model.WalletCardUi
import dev.duardo.neotransfer.plus.ui.model.issuerOf
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Invented data for previews and screenshot tests. Card numbers start with 92 and pass Luhn, and other people's tell
 * their bank and currency as the app reads them; names, accounts and phones belong to nobody.
 */
object PreviewData {
    val zone: ZoneId = ZoneId.of("America/Havana")
    private val now: ZonedDateTime = ZonedDateTime.of(2026, 10, 15, 14, 40, 0, 0, zone)
    val clock: Clock = Clock.fixed(now.toInstant(), zone)

    fun ago(minutes: Long): Instant = now.minusMinutes(minutes).toInstant()
    fun at(daysAgo: Long, hour: Int, minute: Int): Instant = now.minusDays(daysAgo).withHour(hour).withMinute(minute).toInstant()

    fun cup(value: String) = Money(BigDecimal(value), "CUP")

    val bpa = CardUi("c-bpa", "BPA", CardArt.BPA, "9212060123456781", "6781", "CUP", holder = "LAURA MENDEZ SOTO", expiry = "03/29")
    /** ENZONA gave only the last four digits and the user has not completed it. */
    val bandec = CardUi("c-bandec", "BANDEC", CardArt.BANDEC, null, "4325", "CUP", holder = "LAURA MENDEZ SOTO", expiry = "11/30")
    val banmet = CardUi("c-banmet", "Metropolitano", CardArt.BANMET, "9205950012348819", "8819", "CUP", holder = "LAURA MENDEZ SOTO", expiry = "07/28")

    val wallet = listOf(
        WalletCardUi(bpa, BalanceUi.Known(cup("12480.50"), ago(5))),
        WalletCardUi(bandec, BalanceUi.Known(cup("3215.00"), ago(5))),
        WalletCardUi(banmet, BalanceUi.Known(cup("640.25"), ago(5))),
    )

    val account = AccountUi("Laura Méndez Soto", "laura.mendez", "53124578", "laura.mendez@nauta.cu")

    /** The account's cards in Ajustes: BPA is the one ENZONA uses by default. */
    val accountCards = listOf(AccountCardUi(bpa, primary = true), AccountCardUi(bandec, primary = false), AccountCardUi(banmet, primary = false))

    /** A card being added, as typed: its bank and currency come from the number, the holder from the account. */
    val newCard = CardUi("new", "BPA", CardArt.BPA, "9212124503882709", "2709", "CUP", holder = "Laura Méndez Soto", expiry = "08/30")

    // Contacts --------------------------------------------------------------------------------------------------
    private fun card(id: String, number: String, bank: String, label: String? = null, currency: String = "CUP") =
        ForeignCardUi(id, number, bank, label, currency)

    val juan = ContactUi(
        "phone:juan", "Juan Carlos Pérez",
        listOf(card("jc-1", "9204069901447185", "BANDEC", "Salario"), card("jc-2", "9204123331880422", "BPA")),
        listOf("53124578", "64417720"),
        fromAddressBook = true, last = LastMovementUi(cup("1500.00"), ago(52)),
    )
    val maria = ContactUi(
        "phone:maria", "María Fernanda Ruiz", listOf(card("mf-1", "9204067704229016", "BANDEC")), listOf("52981134"),
        fromAddressBook = true, last = LastMovementUi(cup("300.00"), ago(0)),
    )
    val yunior = ContactUi(
        "phone:yunior", "Yunior Castillo", listOf(card("yc-1", "9225061122334453", "BANDEC", currency = "USD")), listOf("56120934"),
        fromAddressBook = true, last = LastMovementUi(cup("5000.00"), ago(12)),
    )
    /** Kept only in the app: no phone in the address book. */
    val carmen = ContactUi("c-carmen", "Abuela Carmen", listOf(card("ca-1", "9205950003124476", "Metropolitano", "Casa"), ForeignCardUi("ca-2", "9204779900001235", label = "Pensión", currency = "CUP")), emptyList())
    val daniela = ContactUi("phone:daniela", "Daniela Ortiz", listOf(card("do-1", "9204120777003196", "BPA")), listOf("58337102"), fromAddressBook = true)
    /** In the address book, no card saved yet. */
    val rafael = ContactUi("phone:rafael", "Rafael Torres", emptyList(), listOf("63660981"), fromAddressBook = true)
    private val carlos = ContactUi("phone:carlos", "Carlos Medina", emptyList(), listOf("55208817"), fromAddressBook = true)
    private val elena = ContactUi("phone:elena", "Elena Vázquez", emptyList(), listOf("54019362"), fromAddressBook = true)
    private val oscar = ContactUi("phone:oscar", "Óscar Núñez", emptyList(), listOf("62750413"), fromAddressBook = true)

    /** The Contacts tab: newest money sent first, then saved cards by name. */
    val people = listOf(maria, yunior, juan, carmen, daniela)
    /** Everyone, by name. */
    val everyone = listOf(carmen, carlos, daniela, elena, juan, maria, oscar, rafael, yunior)
    /** Contacts with a card, as Transferir lists them. */
    val contacts = everyone.filter { it.cards.isNotEmpty() }

    val juanRecipient = RecipientUi.Card(juan.cards.first(), juan.name, juan.id)
    val accountRecipient = RecipientUi.Account("0b6f4e1c-2a7d-4c55-9a51-7c1d2b3e4f50", "ernesto.vidal", "Ernesto Vidal")

    // Operations ------------------------------------------------------------------------------------------------
    val sendingToMaria = OperationUi(
        "op-sending", OperationKind.CardTransfer, "María Fernanda Ruiz", OperationStatus.Sending, ago(0),
        amount = cup("300.00"), card = bpa, counterpart = CounterpartUi.Card("9204067704229016", issuerOf("BANDEC", "CUP")), contactId = maria.id,
    )
    val unconfirmedToYunior = OperationUi(
        "op-unconfirmed", OperationKind.CardTransfer, "Yunior Castillo", OperationStatus.Unconfirmed, ago(12),
        amount = cup("5000.00"), card = bandec, counterpart = CounterpartUi.Card("9225061122334453", issuerOf("BANDEC", "USD")), contactId = yunior.id,
        reason = NoReplyReason,
    )
    val confirmedToJuan = OperationUi(
        "op-juan", OperationKind.CardTransfer, "Juan Carlos Pérez", OperationStatus.Confirmed, ago(52),
        amount = cup("1500.00"), card = bpa, counterpart = CounterpartUi.Card("9204069901447185", issuerOf("BANDEC", "CUP")), contactId = juan.id,
        transactionId = "4f1c9a20-7b3e-4d61-8e0a-52c7d9b1a6e3",
    )
    val incomingFromDaniela = OperationUi(
        "op-in", OperationKind.Incoming, "Daniela Ortiz", OperationStatus.Confirmed, ago(130),
        amount = cup("2000.00"), card = bpa, counterpart = CounterpartUi.CardEnding("3196", "BPA"),
        transactionId = "8a2d6f14-3c90-4b7e-a1d5-0e9f7c2b4a68",
    )
    val qrCafe = OperationUi(
        "op-qr", OperationKind.QrPayment, "Cafetería La Esquina", OperationStatus.Confirmed, at(1, 9, 12),
        amount = cup("480.00"), card = bpa, counterpart = CounterpartUi.Reference("Cafetería La Esquina"),
        transactionId = "c3e5a7b9-1d2f-4a6c-8e0b-2f4d6a8c0e1b", description = "Desayuno",
    )
    val accountTransfer = OperationUi(
        "op-account", OperationKind.AccountTransfer, "Ernesto Vidal", OperationStatus.Confirmed, at(1, 17, 40),
        amount = cup("750.00"), card = banmet, counterpart = CounterpartUi.Account("ernesto.vidal"),
        transactionId = "e7f1b3d5-9a2c-4e6b-8d0f-1a3c5e7b9d2f",
    )
    val landlineRejected = OperationUi(
        "op-rejected", OperationKind.Landline, "Teléfono fijo", OperationStatus.Rejected, at(2, 18, 47),
        amount = cup("155.40"), card = bandec, counterpart = CounterpartUi.Reference("78302214"),
        reason = "ETECSA no aceptó el pago.",
    )
    val electricity = OperationUi(
        "op-elec", OperationKind.Electricity, "Electricidad", OperationStatus.Confirmed, at(3, 11, 30),
        amount = cup("387.10"), card = bpa, counterpart = CounterpartUi.Reference("01234567891"),
        transactionId = "a9c1e3f5-7b2d-4f8a-9c0e-3b5d7f9a1c2e",
    )
    val nauta = OperationUi(
        "op-nauta", OperationKind.Nauta, "Nauta", OperationStatus.Other, at(4, 20, 15),
        amount = cup("250.00"), card = bandec, counterpart = CounterpartUi.Reference("laura.mendez@nauta.com.cu"),
        statusLabel = "En proceso", transactionId = "d2f4a6c8-0e1b-4d3f-a5c7-e9b1d3f5a7c9",
    )

    /** Juan's last movements, as his page lists them. */
    val juanMovements = listOf(
        confirmedToJuan,
        confirmedToJuan.copy(id = "op-juan-2", amount = cup("12480.00"), at = at(3, 10, 5), transactionId = "b1d3f5a7-9c2e-4b6d-8f0a-1c3e5a7b9d2f"),
        confirmedToJuan.copy(id = "op-juan-3", amount = cup("800.00"), at = at(9, 19, 40), transactionId = "f0e2c4a6-8b1d-4f3e-9a5c-7e9b1d3f5a7c"),
    )

    /** Never reached ENZONA: waits on Home to be sent again. */
    val notSentElectricity = OperationUi(
        "op-not-sent", OperationKind.Electricity, "Electricidad", OperationStatus.NotSent, ago(25),
        amount = cup("412.35"), card = bpa, counterpart = CounterpartUi.Reference("01234567891"), reason = NotDeliveredReason,
    )

    val inProgress = listOf(sendingToMaria, unconfirmedToYunior, notSentElectricity)
    val history = listOf(
        sendingToMaria, unconfirmedToYunior, confirmedToJuan, incomingFromDaniela, qrCafe, accountTransfer,
        landlineRejected, electricity, nauta,
    )
    val recent = listOf(confirmedToJuan, incomingFromDaniela, qrCafe, accountTransfer, landlineRejected)

    private fun movement(id: String, title: String, channel: String, amount: String, at: Instant, incoming: Boolean = false) = OperationUi(
        id, if (incoming) OperationKind.Incoming else OperationKind.CardMovement, title, OperationStatus.Confirmed, at,
        amount = cup(amount), card = bpa, channel = channel,
    )

    val cardMovements = listOf(
        movement("m1", "Transferencia a 9204 0699 0144 7185", "ENZONA", "1500.00", ago(52)),
        movement("m2", "Crédito por transferencia", "Transfermóvil", "2000.00", ago(130), incoming = true),
        movement("m3", "Extracción en cajero", "ATM", "1000.00", at(1, 12, 5)),
        movement("m4", "Compra en comercio", "POS", "820.60", at(2, 16, 22)),
        movement("m5", "Pago de electricidad", "ENZONA", "387.10", at(3, 11, 30)),
    )

    const val ownQrPayload = "05b6f71e9d2c4a83"

    // Services --------------------------------------------------------------------------------------------------
    /** Guardados: two named, the rest under their service. */
    val savedReferences = listOf(
        SavedReferenceUi(ServiceKind.Electricity, "01234567891", "Casa", cup("387.10")),
        SavedReferenceUi(ServiceKind.Electricity, "01987654320", lastPaid = cup("142.60")),
        SavedReferenceUi(ServiceKind.Landline, "78302214", "Fijo de la casa", cup("155.40")),
        SavedReferenceUi(ServiceKind.Nauta, "laura.mendez@nauta.com.cu", lastPaid = cup("250.00")),
        SavedReferenceUi(ServiceKind.NautaHome, "laura.mendez.hogar@nauta.com.cu", "Casa de la abuela", cup("1000.00")),
    )

    /** The Nauta accounts of Guardados, as «Recargar» lists them. */
    val rechargeTargets = savedReferences.filter { it.kind == ServiceKind.Nauta || it.kind == ServiceKind.NautaHome }
}
