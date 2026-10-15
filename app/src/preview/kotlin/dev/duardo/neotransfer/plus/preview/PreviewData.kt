package dev.duardo.neotransfer.plus.preview

import dev.duardo.neotransfer.plus.ui.model.AccountUi
import dev.duardo.neotransfer.plus.ui.model.BalanceUi
import dev.duardo.neotransfer.plus.ui.model.CardArt
import dev.duardo.neotransfer.plus.ui.model.CardUi
import dev.duardo.neotransfer.plus.ui.model.ContactUi
import dev.duardo.neotransfer.plus.ui.model.CounterpartUi
import dev.duardo.neotransfer.plus.ui.model.ForeignCardUi
import dev.duardo.neotransfer.plus.ui.model.Money
import dev.duardo.neotransfer.plus.ui.model.OperationKind
import dev.duardo.neotransfer.plus.ui.model.OperationStatus
import dev.duardo.neotransfer.plus.ui.model.OperationUi
import dev.duardo.neotransfer.plus.ui.model.RecentReferenceUi
import dev.duardo.neotransfer.plus.ui.model.RecipientUi
import dev.duardo.neotransfer.plus.ui.model.ServiceKind
import dev.duardo.neotransfer.plus.ui.model.WalletCardUi
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Invented data for previews and screenshot tests. Card numbers start with 92 and pass Luhn; names, accounts and
 * phones belong to nobody.
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
    val banmet = CardUi("c-banmet", "BANMET", CardArt.Neutral, "9225950012348810", "8810", "CUP", holder = "LAURA MENDEZ SOTO", expiry = "07/28")

    val wallet = listOf(
        WalletCardUi(bpa, BalanceUi.Known(cup("12480.50"), ago(5))),
        WalletCardUi(bandec, BalanceUi.Known(cup("3215.00"), ago(5))),
        WalletCardUi(banmet, BalanceUi.Known(cup("640.25"), ago(5))),
    )

    val account = AccountUi("Laura Méndez Soto", "laura.mendez", "53124578", "laura.mendez@nauta.cu")

    // Contacts --------------------------------------------------------------------------------------------------
    val juan = ContactUi(
        "c-juan", "Juan Carlos Pérez",
        listOf(
            ForeignCardUi("jc-1", "9205990144718265", "BANDEC", "Salario"),
            ForeignCardUi("jc-2", "9212333188042154", "BPA"),
        ),
        listOf("53124578", "54417720"),
    )
    val maria = ContactUi("c-maria", "María Fernanda Ruiz", listOf(ForeignCardUi("mf-1", "9205770422901639", "BANDEC")), listOf("52981134"))
    val carmen = ContactUi("c-carmen", "Abuela Carmen", listOf(ForeignCardUi("ca-1", "9225950003124472", "BANMET")), listOf("54417720"))
    val yunior = ContactUi("c-yunior", "Yunior Castillo", listOf(ForeignCardUi("yc-1", "9205112233445565", "BANDEC")), emptyList())
    val daniela = ContactUi("c-daniela", "Daniela Ortiz", listOf(ForeignCardUi("do-1", "9212077700319588", "BPA")), listOf("58337102"))
    val rafael = ContactUi("c-rafael", "Rafael Torres", emptyList(), listOf("53660981"))
    val contacts = listOf(carmen, daniela, juan, maria, rafael, yunior)

    val juanRecipient = RecipientUi.Card(juan.cards.first(), juan.name, juan.id)
    val accountRecipient = RecipientUi.Account("0b6f4e1c-2a7d-4c55-9a51-7c1d2b3e4f50", "ernesto.vidal", "Ernesto Vidal")

    // Operations ------------------------------------------------------------------------------------------------
    val sendingToMaria = OperationUi(
        "op-sending", OperationKind.CardTransfer, "María Fernanda Ruiz", OperationStatus.Sending, ago(0),
        amount = cup("300.00"), card = bpa, counterpart = CounterpartUi.Card("9205770422901639", "BANDEC"), contactId = maria.id,
    )
    val unconfirmedToYunior = OperationUi(
        "op-unconfirmed", OperationKind.CardTransfer, "Yunior Castillo", OperationStatus.Unconfirmed, ago(12),
        amount = cup("5000.00"), card = bandec, counterpart = CounterpartUi.Card("9205112233445565", "BANDEC"), contactId = yunior.id,
        reason = "ENZONA no confirmó la operación. Revisa la actividad antes de repetirla.",
    )
    val confirmedToJuan = OperationUi(
        "op-juan", OperationKind.CardTransfer, "Juan Carlos Pérez", OperationStatus.Confirmed, ago(52),
        amount = cup("1500.00"), card = bpa, counterpart = CounterpartUi.Card("9205990144718265", "BANDEC"), contactId = juan.id,
        transactionId = "4f1c9a20-7b3e-4d61-8e0a-52c7d9b1a6e3",
    )
    val incomingFromDaniela = OperationUi(
        "op-in", OperationKind.Incoming, "Daniela Ortiz", OperationStatus.Confirmed, ago(130),
        amount = cup("2000.00"), card = bpa, counterpart = CounterpartUi.CardEnding("9588", "BPA"),
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

    val inProgress = listOf(sendingToMaria, unconfirmedToYunior)
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
        movement("m1", "Transferencia a 9205 9901 4471 8265", "ENZONA", "1500.00", ago(52)),
        movement("m2", "Crédito por transferencia", "Transfermóvil", "2000.00", ago(130), incoming = true),
        movement("m3", "Extracción en cajero", "ATM", "1000.00", at(1, 12, 5)),
        movement("m4", "Compra en comercio", "POS", "820.60", at(2, 16, 22)),
        movement("m5", "Pago de electricidad", "ENZONA", "387.10", at(3, 11, 30)),
    )

    const val ownQrPayload = "05b6f71e9d2c4a83"

    // Services --------------------------------------------------------------------------------------------------
    val recentReferences = listOf(
        RecentReferenceUi(ServiceKind.Electricity, "01234567891", cup("387.10")),
        RecentReferenceUi(ServiceKind.Nauta, "laura.mendez@nauta.com.cu", cup("250.00")),
    )
}
