package dev.rendy.kirim.reconciliation

import dev.rendy.kirim.account.Account
import dev.rendy.kirim.account.AccountService
import dev.rendy.kirim.support.Auth
import dev.rendy.kirim.support.IntegrationTest
import dev.rendy.kirim.transfer.Transfer
import dev.rendy.kirim.transfer.TransferCommand
import dev.rendy.kirim.transfer.TransferService
import dev.rendy.kirim.transfer.TransferStatus
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Each test builds correct books through the real services, breaks them on
 * purpose with SQL (the kind of damage a bug or a bad manual fix would do),
 * and checks that the reconciliation names exactly what is wrong.
 */
class ReconciliationIntegrationTest : IntegrationTest() {

    @Autowired lateinit var accounts: AccountService
    @Autowired lateinit var transfers: TransferService
    @Autowired lateinit var reconciliation: ReconciliationService
    @Autowired lateinit var mockMvc: MockMvc

    private val jakarta = ZoneId.of("Asia/Jakarta")

    private class Books(
        val alice: Account,
        val bob: Account,
        val carol: Account,
        val aliceToBob: Transfer,
        val bobToCarol: Transfer,
        val rejected: Transfer,
        val day: LocalDate,
    )

    private fun send(from: Account, to: Account, amount: Long): Transfer =
        transfers.transfer(UUID.randomUUID().toString(), TransferCommand(from.id, to.id, amount, "IDR", null)).transfer

    /** 3 accounts (Rp 100.000 deposited in total), 2 completed transfers, 1 rejected, every event delivered. */
    private fun books(): Books {
        val alice = accounts.open("Alice", "IDR", 100_000)
        val bob = accounts.open("Bob", "IDR", 0)
        val carol = accounts.open("Carol", "IDR", 0)
        val aliceToBob = send(alice, bob, 30_000)
        val bobToCarol = send(bob, carol, 10_000)
        val rejected = send(carol, alice, 999_999)
        assertEquals(TransferStatus.REJECTED, rejected.status)
        // The outbox relay is off in tests; pretend Kafka took every event.
        jdbc.sql("UPDATE outbox_events SET status = 'PUBLISHED', published_at = now()").update()
        return Books(alice, bob, carol, aliceToBob, bobToCarol, rejected, LocalDate.ofInstant(aliceToBob.createdAt, jakarta))
    }

    private fun kinds(report: ReconciliationReport) = report.mismatches.map { it.kind }.toSet()

    @Test
    fun `correct books match`() {
        val books = books()

        val report = reconciliation.runManual(books.day)

        assertEquals(RunStatus.MATCHED, report.run.status)
        assertEquals(3, report.run.accountsChecked)
        assertEquals(3, report.run.transfersChecked)
        assertEquals(0, report.run.mismatchCount)
        assertTrue(report.mismatches.isEmpty())
    }

    @Test
    fun `a balance changed outside a transfer is caught, and so is the money it created`() {
        val books = books()
        jdbc.sql("UPDATE accounts SET balance = balance + 5000 WHERE id = :id").param("id", books.bob.id).update()

        val report = reconciliation.runManual(books.day)

        assertEquals(RunStatus.MISMATCH, report.run.status)
        assertEquals(setOf(MismatchKind.BALANCE_MISMATCH, MismatchKind.MONEY_NOT_CONSERVED), kinds(report))
        val balance = report.mismatches.single { it.kind == MismatchKind.BALANCE_MISMATCH }
        assertEquals(books.bob.id, balance.accountId)
        assertEquals(20_000L, balance.expected)
        assertEquals(25_000L, balance.actual)
        val conservation = report.mismatches.single { it.kind == MismatchKind.MONEY_NOT_CONSERVED }
        assertEquals("IDR", conservation.currency)
        assertEquals(100_000L, conservation.expected)
        assertEquals(105_000L, conservation.actual)
    }

    @Test
    fun `a missing ledger leg is caught on the transfer and on the account`() {
        val books = books()
        jdbc.sql("DELETE FROM ledger_entries WHERE transfer_id = :id AND entry_type = 'CREDIT'")
            .param("id", books.bobToCarol.id).update()

        val report = reconciliation.runManual(books.day)

        assertEquals(setOf(MismatchKind.TRANSFER_LEGS_MISMATCH, MismatchKind.BALANCE_MISMATCH), kinds(report))
        val legs = report.mismatches.single { it.kind == MismatchKind.TRANSFER_LEGS_MISMATCH }
        assertEquals(books.bobToCarol.id, legs.transferId)
        assertEquals(2L, legs.expected)
        assertEquals(1L, legs.actual)
        assertEquals(books.carol.id, report.mismatches.single { it.kind == MismatchKind.BALANCE_MISMATCH }.accountId)
    }

    @Test
    fun `a rejected transfer must not have ledger entries`() {
        val books = books()
        jdbc.sql(
            """
            INSERT INTO ledger_entries (id, account_id, transfer_id, entry_type, amount, balance_after, created_at)
            VALUES (:id, :account, :transfer, 'DEBIT', 1, 0, now())
            """,
        ).param("id", UUID.randomUUID()).param("account", books.carol.id).param("transfer", books.rejected.id).update()

        val report = reconciliation.runManual(books.day)

        assertTrue(MismatchKind.REJECTED_WITH_LEDGER_ENTRIES in kinds(report))
        assertEquals(books.rejected.id, report.mismatches.single { it.kind == MismatchKind.REJECTED_WITH_LEDGER_ENTRIES }.transferId)
    }

    @Test
    fun `failed and missing events are caught`() {
        val books = books()
        jdbc.sql("UPDATE outbox_events SET status = 'FAILED' WHERE aggregate_id = :id").param("id", books.aliceToBob.id).update()
        jdbc.sql("DELETE FROM outbox_events WHERE aggregate_id = :id").param("id", books.rejected.id).update()

        val report = reconciliation.runManual(books.day)

        assertEquals(setOf(MismatchKind.EVENT_NOT_PUBLISHED, MismatchKind.EVENT_MISSING), kinds(report))
        assertEquals(books.aliceToBob.id, report.mismatches.single { it.kind == MismatchKind.EVENT_NOT_PUBLISHED }.transferId)
        assertEquals(books.rejected.id, report.mismatches.single { it.kind == MismatchKind.EVENT_MISSING }.transferId)
    }

    @Test
    fun `a PENDING event is in flight for 5 minutes, then it is a mismatch`() {
        val books = books()
        jdbc.sql("UPDATE outbox_events SET status = 'PENDING', published_at = NULL WHERE aggregate_id = :id")
            .param("id", books.aliceToBob.id).update()

        assertEquals(RunStatus.MATCHED, reconciliation.runManual(books.day).run.status)

        jdbc.sql("UPDATE outbox_events SET created_at = now() - interval '1 hour' WHERE aggregate_id = :id")
            .param("id", books.aliceToBob.id).update()

        assertEquals(setOf(MismatchKind.EVENT_NOT_PUBLISHED), kinds(reconciliation.runManual(books.day)))
    }

    @Test
    fun `only transfers of the business day are checked`() {
        val books = books()
        jdbc.sql("UPDATE transfers SET created_at = created_at - interval '2 days' WHERE id = :id")
            .param("id", books.rejected.id).update()
        jdbc.sql("DELETE FROM outbox_events WHERE aggregate_id = :id").param("id", books.rejected.id).update()

        val report = reconciliation.runManual(books.day)

        assertEquals(2, report.run.transfersChecked)
        assertEquals(RunStatus.MATCHED, report.run.status, "the broken transfer belongs to another day")
    }

    @Test
    fun `the nightly run happens once per day, however many replicas try`() {
        val books = books()

        assertNotNull(reconciliation.runScheduled(books.day))
        assertNull(reconciliation.runScheduled(books.day), "a second replica must skip")

        val scheduled = jdbc.sql("SELECT COUNT(*) FROM reconciliation_runs WHERE run_type = 'SCHEDULED'")
            .query(Long::class.javaObjectType).single()
        assertEquals(1L, scheduled)
        assertEquals(RunStatus.MATCHED, reconciliation.runManual(books.day).run.status, "manual runs are always allowed")
    }

    @Test
    fun `run and read reconciliations over HTTP`() {
        val books = books()
        jdbc.sql("UPDATE accounts SET balance = balance + 1 WHERE id = :id").param("id", books.alice.id).update()

        val body = mockMvc.post("/api/v1/reconciliations?date=${books.day}") { with(Auth.ops()) }.andExpect {
            status { isCreated() }
            jsonPath("$.run.status") { value("MISMATCH") }
            jsonPath("$.run.businessDate") { value(books.day.toString()) }
            jsonPath("$.run.runType") { value("MANUAL") }
            jsonPath("$.mismatches.length()") { value(2) }
        }.andReturn().response.contentAsString
        val runId = Regex("\"id\":\"([0-9a-f-]{36})\"").find(body)!!.groupValues[1]

        mockMvc.get("/api/v1/reconciliations/$runId") { with(Auth.ops()) }.andExpect {
            status { isOk() }
            jsonPath("$.mismatches[*].kind") { value(org.hamcrest.Matchers.hasItem("BALANCE_MISMATCH")) }
        }
        mockMvc.get("/api/v1/reconciliations") { with(Auth.ops()) }.andExpect {
            status { isOk() }
            jsonPath("$[0].id") { value(runId) }
        }
        mockMvc.post("/api/v1/reconciliations?date=${books.day.plusDays(2)}") { with(Auth.ops()) }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_REQUEST") }
        }
        mockMvc.get("/api/v1/reconciliations/${UUID.randomUUID()}") { with(Auth.ops()) }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("RECONCILIATION_RUN_NOT_FOUND") }
        }
    }
}
