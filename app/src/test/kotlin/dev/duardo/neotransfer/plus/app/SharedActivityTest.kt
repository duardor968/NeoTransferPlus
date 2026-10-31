package dev.duardo.neotransfer.plus.app

import dev.duardo.neotransfer.plus.core.activity.SyncDirection
import dev.duardo.neotransfer.plus.core.activity.SyncKind
import dev.duardo.neotransfer.plus.core.activity.SyncOperation
import dev.duardo.neotransfer.plus.core.activity.SyncStatus
import dev.duardo.neotransfer.plus.ui.model.CardArt
import dev.duardo.neotransfer.plus.ui.model.CardUi
import dev.duardo.neotransfer.plus.ui.model.CounterpartUi
import dev.duardo.neotransfer.plus.ui.model.Money
import dev.duardo.neotransfer.plus.ui.model.OperationKind
import dev.duardo.neotransfer.plus.ui.model.OperationOrigin
import dev.duardo.neotransfer.plus.ui.model.OperationStatus
import dev.duardo.neotransfer.plus.ui.model.OperationUi
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The activity shared with NeoTransfer: what is exported, and how NeoTransfer's rows join this app's. Synthetic data. */
class SharedActivityTest {
    private val t0 = 1_760_000_000_000
    private val minute = 60_000L
    private val bpa = CardUi("c-bpa", "BPA", CardArt.BPA, "9212060123456781", "6781", "CUP")
    /** ENZONA gave only the last four digits. */
    private val bandec = CardUi("c-bandec", "BANDEC", CardArt.BANDEC, null, "4325", "CUP")

    private fun peer(id: String, card: String = "9212060123456781", amount: String = "500", at: Long = t0, direction: SyncDirection = SyncDirection.OUT,
        kind: SyncKind = SyncKind.CARD_TRANSFER) = SyncOperation(id, kind, SyncStatus.UNCONFIRMED, "Juan Carlos Pérez", at, BigDecimal(amount), "CUP",
        direction, card, "9204069901447185", "phone:juan")

    private fun line(id: String, card: CardUi = bpa, amount: String = "500", at: Long = t0, incoming: Boolean = false) = OperationUi(
        id, if (incoming) OperationKind.Incoming else OperationKind.CardMovement, "Transferencia", OperationStatus.Confirmed, Instant.ofEpochMilli(at),
        amount = Money(BigDecimal(amount), "CUP"), card = card, transactionId = "B-$id", channel = "USSD",
    )

    private val own = OperationUi(
        "tx:1", OperationKind.QrPayment, "Cafetería", OperationStatus.Confirmed, Instant.ofEpochMilli(t0 - 5 * minute),
        amount = Money(BigDecimal("480"), "CUP"), card = bpa,
    )

    @Test fun ownRowsAreExportedAsShownAndStatementLinesNever() {
        val unconfirmed = OperationUi(
            "j:7", OperationKind.CardTransfer, "Juan Carlos Pérez", OperationStatus.Sending, Instant.ofEpochMilli(t0),
            amount = Money(BigDecimal("1500.5"), "CUP"), card = bandec, counterpart = CounterpartUi.Card("9204069901447185"), contactId = "phone:juan",
        )
        val incoming = own.copy(id = "tx:2", kind = OperationKind.Incoming, title = "Daniela", counterpart = CounterpartUi.CardEnding("3196"))
        val rows = exportedActivity(listOf(own, unconfirmed, incoming, line("m:c-bpa:0"), own.copy(id = "tx:3", at = null)), t0)
        assertEquals(listOf("j:7", "tx:1", "tx:2"), rows.map { it.id })
        val sent = rows.first()
        assertEquals(SyncKind.CARD_TRANSFER, sent.kind)
        assertEquals(SyncStatus.PENDING, sent.status)
        assertEquals("4325", sent.card)
        assertEquals("9204069901447185", sent.counterpart)
        assertEquals("phone:juan", sent.contactId)
        assertEquals(SyncDirection.OUT, sent.direction)
        assertEquals("9212060123456781", rows[1].card)
        assertEquals(SyncDirection.IN, rows[2].direction)
        assertEquals("3196", rows[2].counterpart)
    }

    @Test fun nothingChangesWithoutNeoTransfer() {
        val activity = listOf(own)
        val statement = listOf(line("m:c-bpa:0"))
        val peer = peerRows(null, statement, listOf(bpa))
        assertNull(peer)
        assertSame(activity, withPeer(activity, peer))
        assertSame(statement, statement.withPeerLines(peer))
        assertTrue(withPeer(activity, peer).all { it.origin == null })
    }

    @Test fun withNeoTransferEveryRowCarriesItsAppsDot() {
        val peer = peerRows(listOf(peer("u1", at = t0 + 30 * minute)), emptyList(), listOf(bpa))!!
        val rows = withPeer(listOf(own), peer)
        assertEquals(listOf("p:u1", "tx:1"), rows.map { it.id })
        assertEquals(listOf(OperationOrigin.NeoTransfer, OperationOrigin.Enzona), rows.map { it.origin })
        val row = rows.first()
        assertTrue(row.readOnly)
        assertEquals(bpa, row.card)
        assertEquals(OperationStatus.Unconfirmed, row.status)
        assertEquals(CounterpartUi.Card("9204069901447185", Banks.issuerOf("9204069901447185")), row.counterpart)
    }

    @Test fun theSameMovementBecomesOneRowWithBothSidesData() {
        val statement = listOf(line("m:c-bpa:0"), line("m:c-bpa:1", amount = "20"))
        val peer = peerRows(listOf(peer("u1", at = t0 + 4 * minute)), statement, listOf(bpa))!!
        val merged = peer.single()
        assertEquals("m:c-bpa:0", merged.id)
        assertEquals(OperationKind.CardTransfer, merged.kind)
        assertEquals("Juan Carlos Pérez", merged.title)
        assertEquals("phone:juan", merged.contactId)
        assertEquals(OperationOrigin.NeoTransfer, merged.origin)
        // The bank's record stands: its state, code and channel; it can be shared like any statement line.
        assertEquals(OperationStatus.Confirmed, merged.status)
        assertEquals("B-m:c-bpa:0", merged.transactionId)
        assertEquals(false, merged.readOnly)
        assertEquals(listOf(merged, statement[1]), statement.withPeerLines(peer))
        assertEquals(listOf("m:c-bpa:0", "tx:1"), withPeer(listOf(own), peer).map { it.id })
    }

    @Test fun theTimeWindowIsTenMinutesEitherWay() {
        fun merges(at: Long) = peerRows(listOf(peer("u1", at = at)), listOf(line("m:c-bpa:0")), listOf(bpa))!!.single().id.startsWith("m:")
        assertTrue(merges(t0 + 10 * minute))
        assertTrue(merges(t0 - 10 * minute))
        assertEquals(false, merges(t0 + 10 * minute + 1))
        assertEquals(false, merges(t0 - 10 * minute - 1))
    }

    @Test fun onlyOneCandidateOnEachSideMerges() {
        // Two lines could be it: neither is taken.
        val twoLines = peerRows(listOf(peer("u1")), listOf(line("m:a"), line("m:b", at = t0 + minute)), listOf(bpa))!!
        assertEquals(listOf("p:u1"), twoLines.map { it.id })
        // Two of NeoTransfer's operations could be the one line: neither takes it.
        val twoOps = peerRows(listOf(peer("u1"), peer("u2", at = t0 + minute)), listOf(line("m:a")), listOf(bpa))!!
        assertEquals(listOf("p:u1", "p:u2"), twoOps.map { it.id })
    }

    @Test fun cardAmountAndDirectionMustAgree() {
        fun merged(peer: SyncOperation, line: OperationUi) = peerRows(listOf(peer), listOf(line), listOf(bpa, bandec))!!.single().id.startsWith("m:")
        assertEquals(false, merged(peer("u1", amount = "500.01"), line("m:a")))
        assertEquals(false, merged(peer("u1", direction = SyncDirection.IN), line("m:a")))
        assertEquals(false, merged(peer("u1", card = "9212060123450000"), line("m:a")))
        assertTrue(merged(peer("u1", amount = "500.00"), line("m:a")))
        assertTrue(merged(peer("u1", direction = SyncDirection.IN, kind = SyncKind.INCOMING), line("m:a", incoming = true)))
    }

    @Test fun lastFourDigitsMatchWhenOneSideHasNoMore() {
        // NeoTransfer knows the full number, ENZONA only the last four.
        assertTrue(peerRows(listOf(peer("u1", card = "9204129900004325")), listOf(line("m:a", card = bandec)), listOf(bpa, bandec))!!.single().id == "m:a")
        // NeoTransfer gives the last four only.
        assertTrue(peerRows(listOf(peer("u1", card = "6781")), listOf(line("m:a")), listOf(bpa, bandec))!!.single().id == "m:a")
        assertEquals(false, peerRows(listOf(peer("u1", card = "")), listOf(line("m:a")), listOf(bpa))!!.single().id == "m:a")
    }

    @Test fun originatedOperationsNeverMergeWithEachOther() {
        // An ENZONA payment of the same card, amount and time is still its own row next to NeoTransfer's.
        val enzona = own.copy(id = "tx:9", kind = OperationKind.CardTransfer, at = Instant.ofEpochMilli(t0), amount = Money(BigDecimal("500"), "CUP"))
        val rows = withPeer(listOf(enzona), peerRows(listOf(peer("u1")), emptyList(), listOf(bpa)))
        assertEquals(setOf("tx:9", "p:u1"), rows.map { it.id }.toSet())
    }

    @Test fun aCardOnlyNeoTransferKnowsIsToldByItsNumber() {
        val row = peerRows(listOf(peer("u1", card = "9205950012348819")), emptyList(), listOf(bpa))!!.single()
        assertEquals("Metropolitano", row.card?.bank)
        assertEquals("8819", row.card?.last4)
        assertEquals("9205950012348819", row.card?.number)
    }
}
