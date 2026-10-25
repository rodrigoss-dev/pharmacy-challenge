package pharmacy.infra

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import pharmacy.domain.MedicationId
import pharmacy.domain.Prescription
import pharmacy.domain.PrescriptionStatus
import pharmacy.domain.PrescriptionStatus.AWAITING_APPROVAL
import pharmacy.domain.PrescriptionStatus.OUT_OF_STOCK
import pharmacy.domain.PrescriptionStatus.RECEIVED
import pharmacy.domain.PrescriptionStatus.REJECTED
import pharmacy.domain.port.InventoryRepository
import pharmacy.domain.port.PrescriptionRepository
import pharmacy.domain.usecase.MedicationCatalog
import pharmacy.domain.usecase.ReserveStock
import pharmacy.domain.usecase.StaffActions
import pharmacy.domain.usecase.SubmitPrescription
import pharmacy.infra.messaging.StockCheckSweeper
import pharmacy.infra.persistence.Transactions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Several patients, one pack left: the earliest ticket gets it, however the stock checks race. They run as they do in
 * production: each in its own transaction, through [Transactions], on threads of their own (the consumer on one
 * instance, the sweeper or another instance on another).
 */
@QuarkusTest
class LastPackRaceTest {
    @Inject lateinit var flyway: Flyway
    @Inject lateinit var submit: SubmitPrescription
    @Inject lateinit var reserveStock: ReserveStock
    @Inject lateinit var staff: StaffActions
    @Inject lateinit var catalog: MedicationCatalog
    @Inject lateinit var prescriptions: PrescriptionRepository
    @Inject lateinit var inventory: InventoryRepository
    @Inject lateinit var transactions: Transactions
    @Inject lateinit var sweeper: StockCheckSweeper

    private val threads = Executors.newCachedThreadPool()
    private lateinit var ana: Prescription

    @BeforeEach
    fun lastPackLeft() {
        flyway.reset()
        // 3 atorvastatin to start with: Ana takes 2, leaving the last one.
        ana = checkIn("Ana", 2)
        assertEquals(AWAITING_APPROVAL, reserveStock(ana.id).status)
        assertEquals(1, stock())
    }

    private fun checkIn(name: String, packs: Int = 1) = submit(
        SubmitPrescription.Command(null, name, listOf(SubmitPrescription.Command.Item(ATORVASTATIN, packs))),
    )

    private fun stock() = inventory.findByIds(listOf(ATORVASTATIN)).single().stock
    private fun status(p: Prescription) = prescriptions.findById(p.id)!!.status
    private fun stockCheck(p: Prescription) = transactions.transaction("stock check for #${p.ticketNumber}") { reserveStock(p.id) }

    /** Packs on the shelf plus packs reserved by prescriptions that hold them: never more or fewer than there are. */
    private fun packsAccountedFor(): Int {
        val holding = prescriptions.findByStatus(HOLDING_STOCK).sumOf { p -> p.items.filter { it.medicationId == ATORVASTATIN }.sumOf { it.quantity } }
        return stock() + holding
    }

    @Test
    fun `both stock checks start at the same instant, the earlier ticket gets the pack and the later one is told it is out of stock`() {
        repeat(ROUNDS) { round ->
            val maria = checkIn("Maria")
            val joao = checkIn("Joao")
            val start = CyclicBarrier(2)
            // Joao's goes in first, to give him every chance to overtake.
            val results = listOf(joao, maria).map { p -> p to threads.submit<PrescriptionStatus> { start.await(); stockCheck(p).status } }
                .associate { (p, result) -> p.ticketNumber to result.get(30, TimeUnit.SECONDS) }

            assertEquals(mapOf(maria.ticketNumber to AWAITING_APPROVAL, joao.ticketNumber to OUT_OF_STOCK), results, "round $round")
            assertEquals(AWAITING_APPROVAL, status(maria), "round $round")
            assertEquals(OUT_OF_STOCK, status(joao), "round $round")
            assertEquals(0, stock(), "round $round: never below zero, never left behind")
            assertEquals(3 + round, packsAccountedFor(), "round $round")
            catalog.restock(ATORVASTATIN, 1) // the last pack again, for the next round
        }
    }

    @Test
    fun `when the later ticket's stock check runs first, the earlier ticket is served ahead of it`() {
        val maria = checkIn("Maria")
        val joao = checkIn("Joao")

        val result = stockCheck(joao) // Joao's message arrived first

        assertEquals(OUT_OF_STOCK, result.status)
        assertEquals(AWAITING_APPROVAL, status(maria), "Maria checked in first: the pack is hers")
        assertEquals(0, stock())

        // Maria's own message comes in late and finds nothing left to do.
        assertEquals(AWAITING_APPROVAL, stockCheck(maria).status)
        assertEquals(3, packsAccountedFor())
    }

    @Test
    fun `a stock check serves the patients ahead of it, and nobody behind it`() {
        val maria = checkIn("Maria")
        val joao = checkIn("Joao")

        stockCheck(maria)
        assertEquals(RECEIVED, status(joao), "Joao checked in after Maria: his stock check is his own")

        reserveStock(ana.id) // a repeated message for Ana, who was served long ago
        assertEquals(RECEIVED, status(joao), "a message for a patient already served does not run the others' checks")
    }

    @Test
    fun `the second stock check waits for the first to commit, then finds none left`() {
        val maria = checkIn("Maria")
        val joao = checkIn("Joao")
        val mariaReserved = CountDownLatch(1)

        val first = threads.submit<Prescription> {
            transactions.transaction("stock check for Maria, held open") {
                reserveStock(maria.id).also {
                    mariaReserved.countDown()
                    Thread.sleep(HOLD_MS) // the stock check lock stays taken until this commits
                }
            }
        }
        assertTrue(mariaReserved.await(10, TimeUnit.SECONDS))
        val started = System.nanoTime()
        val second = stockCheck(joao)
        val waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertEquals(AWAITING_APPROVAL, first.get(10, TimeUnit.SECONDS).status)
        assertEquals(OUT_OF_STOCK, second.status)
        assertEquals("Not enough stock of Atorvastatin 20 mg", second.statusReason)
        assertTrue(waitedMs >= HOLD_MS / 2, "Joao's check should have waited on Maria's lock, waited $waitedMs ms")
        assertEquals(0, stock())
    }

    @Test
    fun `if the first stock check crashes, the next one still serves the earlier ticket first`() {
        val maria = checkIn("Maria")
        val joao = checkIn("Joao")
        val mariaReserved = CountDownLatch(1)

        val first = threads.submit {
            transactions.transaction("stock check for Maria, then a crash") {
                reserveStock(maria.id)
                mariaReserved.countDown()
                Thread.sleep(HOLD_MS)
                error("the instance dies before committing")
            }
        }
        assertTrue(mariaReserved.await(10, TimeUnit.SECONDS))
        val second = stockCheck(joao)

        assertTrue(runCatching { first.get(10, TimeUnit.SECONDS) }.isFailure)
        // Nothing of Maria's check was saved, so Joao's finds her still waiting, ahead of him, and serves her first.
        assertEquals(OUT_OF_STOCK, second.status)
        assertEquals(AWAITING_APPROVAL, status(maria))
        assertEquals(0, stock())
        assertEquals(3, packsAccountedFor())
    }

    @Test
    fun `the same patient checked twice at once (consumer and sweeper) takes the pack once`() {
        val maria = checkIn("Maria")
        val reserved = CountDownLatch(1)

        val consumer = threads.submit<Prescription> {
            transactions.transaction("stock check for Maria (consumer)") {
                reserveStock(maria.id).also { reserved.countDown(); Thread.sleep(HOLD_MS) }
            }
        }
        assertTrue(reserved.await(10, TimeUnit.SECONDS))
        // The sweeper waits its turn, then finds Maria served: it does not mark her out of stock, nor fail on a conflict.
        assertEquals(AWAITING_APPROVAL, stockCheck(maria).status)

        assertEquals(AWAITING_APPROVAL, consumer.get(10, TimeUnit.SECONDS).status)
        assertEquals(AWAITING_APPROVAL, status(maria))
        assertEquals(0, stock())
        assertEquals(3, packsAccountedFor())
    }

    @Test
    fun `ten patients, two instances sweeping at once, exactly one gets the pack and it is the earliest ticket`() {
        val waiting = (1..10).map { checkIn("Patient $it") }
        val start = CyclicBarrier(2)
        listOf(threads.submit { start.await(); sweeper.sweep() }, threads.submit { start.await(); sweeper.sweep() })
            .forEach { it.get(60, TimeUnit.SECONDS) }

        val statuses = waiting.associate { it.ticketNumber to status(it) }
        assertEquals(1, statuses.values.count { it == AWAITING_APPROVAL }, "$statuses")
        assertEquals(9, statuses.values.count { it == OUT_OF_STOCK }, "$statuses")
        assertEquals(AWAITING_APPROVAL, statuses.getValue(waiting.first().ticketNumber), "$statuses")
        assertEquals(0, stock())
        assertEquals(3, packsAccountedFor())
    }

    @Test
    fun `ten patients, each checked on its own thread (several consumers), the earliest ticket gets the pack and nothing is lost`() {
        repeat(5) { round ->
            val waiting = (1..10).map { checkIn("Patient $it") }
            val start = CyclicBarrier(waiting.size)
            waiting.reversed().map { p -> threads.submit<Prescription> { start.await(); stockCheck(p) } }
                .forEach { it.get(60, TimeUnit.SECONDS) }

            val statuses = waiting.associate { it.ticketNumber to status(it) }
            assertEquals(1, statuses.values.count { it == AWAITING_APPROVAL }, "round $round: $statuses")
            assertEquals(AWAITING_APPROVAL, statuses.getValue(waiting.first().ticketNumber), "round $round: $statuses")
            assertEquals(0, stock(), "round $round")
            assertEquals(3 + round, packsAccountedFor(), "round $round")
            catalog.restock(ATORVASTATIN, 1)
        }
    }

    @Test
    fun `a rejection gives the pack back while another patient's stock check runs, and the pack is never lost or doubled`() {
        var packs = 3
        repeat(ROUNDS) { round ->
            val holder = checkIn("Holder")
            assertEquals(AWAITING_APPROVAL, reserveStock(holder.id).status, "round $round")
            val next = checkIn("Next")
            val start = CyclicBarrier(2)
            listOf(
                threads.submit { start.await(); transactions.transaction("reject") { staff.reject(holder.id, "Wrong dose") } },
                threads.submit { start.await(); stockCheck(next) },
            ).forEach { it.get(30, TimeUnit.SECONDS) }

            assertEquals(REJECTED, status(holder))
            val nextStatus = status(next)
            assertEquals(if (nextStatus == AWAITING_APPROVAL) 0 else 1, stock(), "round $round: $nextStatus")
            assertEquals(packs, packsAccountedFor(), "round $round")
            if (nextStatus == AWAITING_APPROVAL) {
                catalog.restock(ATORVASTATIN, 1) // the last pack again, for the next holder
                packs++
            }
        }
    }

    private companion object {
        val ATORVASTATIN = MedicationId("atorvastatin")
        val HOLDING_STOCK = listOf(
            PrescriptionStatus.AWAITING_APPROVAL,
            PrescriptionStatus.AWAITING_PACKAGING,
            PrescriptionStatus.READY_FOR_PICKUP,
            PrescriptionStatus.CALLED,
            PrescriptionStatus.COLLECTED,
        )
        const val ROUNDS = 20
        const val HOLD_MS = 800L
    }
}
