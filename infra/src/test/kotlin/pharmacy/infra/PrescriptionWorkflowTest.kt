package pharmacy.infra

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import pharmacy.domain.CheckInIdReusedException
import pharmacy.domain.ConcurrentUpdateException
import pharmacy.domain.IllegalTransitionException
import pharmacy.domain.MedicationId
import pharmacy.domain.PrescriptionId
import pharmacy.domain.PrescriptionStatus.AWAITING_APPROVAL
import pharmacy.domain.PrescriptionStatus.COLLECTED
import pharmacy.domain.PrescriptionStatus.NO_SHOW
import pharmacy.domain.PrescriptionStatus.OUT_OF_STOCK
import pharmacy.domain.PrescriptionStatus.REJECTED
import pharmacy.domain.ValidationException
import pharmacy.domain.port.InventoryRepository
import pharmacy.domain.port.PrescriptionRepository
import pharmacy.domain.usecase.ReserveStock
import pharmacy.domain.usecase.StaffActions
import pharmacy.domain.usecase.SubmitPrescription
import pharmacy.domain.usecase.TrackPrescription
import pharmacy.infra.persistence.JooqEventOutbox
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The use cases running against a real PostgreSQL database, migrated afresh (with the starting stock) for every test. */
@QuarkusTest
class PrescriptionWorkflowTest {
    @Inject lateinit var flyway: Flyway
    @Inject lateinit var submit: SubmitPrescription
    @Inject lateinit var reserveStock: ReserveStock
    @Inject lateinit var staff: StaffActions
    @Inject lateinit var track: TrackPrescription
    @Inject lateinit var prescriptions: PrescriptionRepository
    @Inject lateinit var inventory: InventoryRepository
    @Inject lateinit var outbox: JooqEventOutbox
    @Inject lateinit var clock: Clock

    @BeforeEach
    fun freshDatabase() = flyway.reset()

    private fun checkIn(vararg items: Pair<String, Int>, id: PrescriptionId? = null) = submit(
        SubmitPrescription.Command(id, "Maria", items.map { (med, qty) -> SubmitPrescription.Command.Item(MedicationId(med), qty) }),
    )

    private fun stock(id: String) = inventory.findByIds(listOf(MedicationId(id))).single().stock

    @Test
    fun `full cycle reserves stock and records one event per step`() {
        val prescription = checkIn("amoxicillin" to 2, "ibuprofen" to 1)
        assertEquals(1, outbox.backlogDetails().events)

        assertEquals(AWAITING_APPROVAL, reserveStock(prescription.id).status)
        assertEquals(38, stock("amoxicillin"))
        assertEquals(59, stock("ibuprofen"))

        staff.approve(prescription.id)
        staff.markPacked(prescription.id)
        staff.callPatient(prescription.id)
        val collected = staff.handOver(prescription.id)

        assertEquals(COLLECTED, collected.status)
        assertEquals(6, outbox.backlogDetails().events) // received, reserved, approved, packed, called, collected
    }

    @Test
    fun `out of stock is all or nothing`() {
        val prescription = checkIn("amoxicillin" to 1, "atorvastatin" to 4) // only 3 atorvastatin in stock

        val result = reserveStock(prescription.id)

        assertEquals(OUT_OF_STOCK, result.status)
        assertEquals("Not enough stock of Atorvastatin 20 mg", result.statusReason)
        assertEquals(40, stock("amoxicillin")) // the amoxicillin reserved first was put back
        assertEquals(3, stock("atorvastatin"))
    }

    @Test
    fun `stock check is idempotent, since messages can be delivered twice`() {
        val prescription = checkIn("metformin" to 2)

        reserveStock(prescription.id)
        reserveStock(prescription.id)

        assertEquals(28, stock("metformin"))
        assertEquals(2, outbox.backlogDetails().events)
    }

    @Test
    fun `rejecting puts the reserved stock back`() {
        val prescription = checkIn("lisinopril" to 3)
        reserveStock(prescription.id)
        assertEquals(27, stock("lisinopril"))

        val rejected = staff.reject(prescription.id, "Dose looks wrong, please see the pharmacist")

        assertEquals(REJECTED, rejected.status)
        assertEquals(30, stock("lisinopril"))
    }

    @Test
    fun `a no show puts the reserved stock back, and only a called number can be one`() {
        val prescription = checkIn("lisinopril" to 3, "metformin" to 2)
        reserveStock(prescription.id)
        assertEquals(27, stock("lisinopril"))
        assertEquals(28, stock("metformin"))
        staff.approve(prescription.id)
        staff.markPacked(prescription.id)
        assertFailsWith<IllegalTransitionException> { staff.noShow(prescription.id) }
        assertEquals(27, stock("lisinopril"), "nobody has been called yet: nothing changes")

        staff.callPatient(prescription.id)
        staff.callPatient(prescription.id) // tried again, still no answer
        val noShow = staff.noShow(prescription.id)

        assertEquals(NO_SHOW, noShow.status)
        assertEquals(30, stock("lisinopril"))
        assertEquals(30, stock("metformin"))
        assertFailsWith<IllegalTransitionException> { staff.handOver(prescription.id) }
        assertFailsWith<IllegalTransitionException> { staff.noShow(prescription.id) }
        assertEquals(30, stock("lisinopril"), "put back once, not twice")
    }

    @Test
    fun `checking in twice with the same id returns the same ticket`() {
        val id = PrescriptionId.random()

        val first = checkIn("cetirizine" to 1, id = id)
        val retry = checkIn("cetirizine" to 1, id = id)

        assertEquals(first, retry)
        assertEquals(1, outbox.backlogDetails().events)
    }

    @Test
    fun `an id that was used for one check-in is not accepted for another`() {
        val id = PrescriptionId.random()
        val first = checkIn("cetirizine" to 2, id = id)

        // A retry, even with stray spaces around the name: the same check-in.
        val retry = submit(
            SubmitPrescription.Command(id, "  Maria ", listOf(SubmitPrescription.Command.Item(MedicationId("cetirizine"), 2))),
        )
        assertEquals(first, retry)

        val other = assertFailsWith<CheckInIdReusedException> { checkIn("cetirizine" to 1, id = id) }
        assertEquals("This check-in was already sent with other details. Please start a new check-in.", other.message)
        assertFailsWith<CheckInIdReusedException> { checkIn("ibuprofen" to 2, id = id) }
        assertFailsWith<CheckInIdReusedException> { checkIn("cetirizine" to 2, "ibuprofen" to 1, id = id) }
        assertFailsWith<CheckInIdReusedException> {
            submit(SubmitPrescription.Command(id, "Joao", listOf(SubmitPrescription.Command.Item(MedicationId("cetirizine"), 2))))
        }

        assertEquals(1, outbox.backlogDetails().events, "only the first check-in was recorded")
        assertEquals(first, prescriptions.findById(id))
    }

    @Test
    fun `a refused check-in takes no ticket number`() {
        assertFailsWith<ValidationException> { checkIn("ibuprofen" to 0) }
        assertFailsWith<ValidationException> { checkIn("nope" to 1) }
        assertFailsWith<ValidationException> { checkIn("ibuprofen" to 1, "ibuprofen" to 1) }

        assertEquals(1, checkIn("ibuprofen" to 1).ticketNumber)
    }

    @Test
    fun `tickets are numbered in order and the queue position counts earlier tickets`() {
        val tickets = (1..3).map { checkIn("omeprazole" to 1).also { reserveStock(it.id) } }

        assertEquals(listOf(1, 2, 3), tickets.map { it.ticketNumber })
        val last = prescriptions.findById(tickets.last().id)!!
        assertEquals(2, prescriptions.countAhead(last))
    }

    @Test
    fun `people ahead are counted where patients wait for staff, not during the stock check or at the pickup`() {
        val first = checkIn("omeprazole" to 1)
        val second = checkIn("omeprazole" to 1)
        assertEquals(0, track(second.id).peopleAhead, "the stock check takes a second")

        reserveStock(first.id)
        reserveStock(second.id)
        assertEquals(1, track(second.id).peopleAhead)

        staff.approve(first.id)
        staff.approve(second.id)
        assertEquals(1, track(second.id).peopleAhead)

        staff.markPacked(first.id)
        staff.markPacked(second.id)
        assertEquals(0, track(second.id).peopleAhead, "called by number, not in line")
    }

    @Test
    fun `relaying takes the oldest events and removes only what it was given`() {
        checkIn("ibuprofen" to 1)
        checkIn("ibuprofen" to 2)
        checkIn("ibuprofen" to 3)

        val oldest = outbox.lockOldest(2)
        assertEquals(listOf("prescription.received", "prescription.received"), oldest.map { it.routingKey })
        outbox.remove(oldest.map { it.id })

        assertEquals(1, outbox.backlogDetails().events)
    }

    @Test
    fun `invalid steps and concurrent changes are refused`() {
        val prescription = checkIn("ibuprofen" to 1)
        assertFailsWith<IllegalTransitionException> { staff.approve(prescription.id) } // stock not checked yet

        val stale = prescriptions.findById(prescription.id)!!
        reserveStock(prescription.id)
        val refused = assertFailsWith<ConcurrentUpdateException> {
            prescriptions.update(stale.stockReserved(clock.instant()))
        }
        // Staff read this on the console: the ticket they see, not the id behind it.
        assertEquals(
            "Prescription #${prescription.ticketNumber} was changed by someone else at the same moment, so this step was not saved: check where it is now",
            refused.message,
        )
    }
}
