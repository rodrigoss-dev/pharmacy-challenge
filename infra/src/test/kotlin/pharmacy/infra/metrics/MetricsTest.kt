package pharmacy.infra.metrics

import io.micrometer.core.instrument.MeterRegistry
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.get
import jakarta.inject.Inject
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import pharmacy.domain.MedicationId
import pharmacy.domain.PrescriptionStatus
import pharmacy.domain.usecase.ReserveStock
import pharmacy.domain.usecase.SubmitPrescription
import pharmacy.infra.persistence.Transactions
import pharmacy.infra.reset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The Prometheus metrics, with a real database and no broker (so every event stays in the outbox). */
@QuarkusTest
class MetricsTest {
    @Inject lateinit var flyway: Flyway
    @Inject lateinit var registry: MeterRegistry
    @Inject lateinit var refresh: MetricsRefresh
    @Inject lateinit var submit: SubmitPrescription
    @Inject lateinit var reserveStock: ReserveStock
    @Inject lateinit var transactions: Transactions

    @BeforeEach
    fun freshDatabase() = flyway.reset()

    private fun checkIn(medication: String, packs: Int) =
        submit(SubmitPrescription.Command(null, "Maria", listOf(SubmitPrescription.Command.Item(MedicationId(medication), packs))))

    /** The counters live as long as the application: tests look at what changed, not at the total. */
    private fun statusChanges(status: PrescriptionStatus) =
        registry.counter("pharmacy.prescription.status.changes", "status", status.name).count()

    private fun gauge(name: String, vararg tags: String) = registry.get(name).tags(*tags).gauge().value()

    @Test
    fun `status changes are counted, and the database gauges say where everything stands`() {
        val receivedBefore = statusChanges(PrescriptionStatus.RECEIVED)
        val approvalBefore = statusChanges(PrescriptionStatus.AWAITING_APPROVAL)
        val outOfStockBefore = statusChanges(PrescriptionStatus.OUT_OF_STOCK)

        val filled = checkIn("amoxicillin", 2)
        val short = checkIn("atorvastatin", 5) // three in stock
        reserveStock(filled.id)
        reserveStock(short.id)
        refresh.refresh()

        assertEquals(2.0, statusChanges(PrescriptionStatus.RECEIVED) - receivedBefore)
        assertEquals(1.0, statusChanges(PrescriptionStatus.AWAITING_APPROVAL) - approvalBefore)
        assertEquals(1.0, statusChanges(PrescriptionStatus.OUT_OF_STOCK) - outOfStockBefore)

        assertEquals(0.0, gauge("pharmacy.prescriptions", "status", "RECEIVED"))
        assertEquals(1.0, gauge("pharmacy.prescriptions", "status", "AWAITING_APPROVAL"))
        assertEquals(1.0, gauge("pharmacy.prescriptions", "status", "OUT_OF_STOCK"))
        assertTrue(gauge("pharmacy.prescription.oldest.wait", "status", "AWAITING_APPROVAL") >= 0.0)
        assertEquals(38.0, gauge("pharmacy.medication.stock", "medication", "Amoxicillin 500 mg"))
        assertEquals(3.0, gauge("pharmacy.medication.stock", "medication", "Atorvastatin 20 mg"))

        // The system side: two check-ins and two status changes wait for a broker that is not there.
        assertEquals(4.0, gauge("pharmacy.outbox.pending.events"))
        assertEquals(0.0, gauge("pharmacy.stock.checks.stalled"))
        assertEquals(1.0, gauge("pharmacy.database.up"))
        assertEquals(0.0, gauge("pharmacy.broker.up"))
    }

    @Test
    fun `a change that rolls back is not counted`() {
        val before = statusChanges(PrescriptionStatus.RECEIVED)

        assertFailsWith<IllegalStateException> {
            transactions.transaction("check-in that fails") {
                checkIn("amoxicillin", 1)
                error("something after the check-in went wrong")
            }
        }
        assertEquals(before, statusChanges(PrescriptionStatus.RECEIVED))

        transactions.transaction("check-in") { checkIn("amoxicillin", 1) }
        assertEquals(before + 1, statusChanges(PrescriptionStatus.RECEIVED))
    }

    @Test
    fun `Prometheus can scrape them`() {
        checkIn("ibuprofen", 1)
        refresh.refresh()
        get("/api/medications") // so there is a request to time

        val metrics = get("/q/metrics").also { assertEquals(200, it.statusCode) }.asString()
        for (expected in listOf(
            """pharmacy_prescriptions{status="RECEIVED"} 1.0""",
            "pharmacy_prescription_status_changes_total{",
            """pharmacy_medication_stock{medication="Ibuprofen 400 mg"} 60.0""",
            "pharmacy_prescription_oldest_wait_seconds{",
            "pharmacy_outbox_pending_events ",
            "pharmacy_database_up 1.0",
            "http_server_requests_seconds_bucket{",
            "agroal_",
            "jvm_memory_used_bytes{",
        )) {
            assertTrue(expected in metrics, "$expected is missing from /q/metrics")
        }
    }
}
