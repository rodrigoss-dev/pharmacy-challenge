package pharmacy.infra.messaging

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import pharmacy.domain.ConcurrentUpdateException
import pharmacy.domain.MedicationId
import pharmacy.domain.PrescriptionStatus.AWAITING_APPROVAL
import pharmacy.domain.PrescriptionStatus.OUT_OF_STOCK
import pharmacy.domain.PrescriptionStatus.RECEIVED
import pharmacy.domain.port.InventoryRepository
import pharmacy.domain.port.PrescriptionRepository
import pharmacy.domain.usecase.PrescriptionUpdater
import pharmacy.domain.usecase.ReserveStock
import pharmacy.domain.usecase.StalledStockChecks
import pharmacy.domain.usecase.SubmitPrescription
import pharmacy.infra.persistence.DatabaseHealth
import pharmacy.infra.persistence.ToggleDataSource
import pharmacy.infra.persistence.Transactions
import pharmacy.infra.reset
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** No broker in tests: like an outage, the stock check messages never arrive. The sweeper (after 0s here) steps in. */
@QuarkusTest
class StockCheckSweeperTest {
    @Inject lateinit var flyway: Flyway
    @Inject lateinit var submit: SubmitPrescription
    @Inject lateinit var prescriptions: PrescriptionRepository
    @Inject lateinit var sweeper: StockCheckSweeper
    @Inject lateinit var stalled: StalledStockChecks

    @BeforeEach
    fun freshDatabase() = flyway.reset()

    private fun checkIn(medication: String, packs: Int) =
        submit(SubmitPrescription.Command(null, "Maria", listOf(SubmitPrescription.Command.Item(MedicationId(medication), packs))))

    @Test
    fun `runs the stock checks whose message never came, once`() {
        val available = checkIn("ibuprofen", 2)
        val short = checkIn("atorvastatin", 5) // only 3 in stock

        assertEquals(2, sweeper.sweep())

        assertEquals(AWAITING_APPROVAL, prescriptions.findById(available.id)!!.status)
        assertEquals(OUT_OF_STOCK, prescriptions.findById(short.id)!!.status)
        assertEquals(0, sweeper.sweep()) // nothing left waiting
    }

    @Test
    fun `stalled stock checks are counted as many as are listed`() {
        checkIn("ibuprofen", 1)
        checkIn("ibuprofen", 1)

        assertEquals(2, stalled(Duration.ZERO).size)
        assertEquals(2, stalled.count(Duration.ZERO))
        assertEquals(0, stalled.count(Duration.ofHours(1)), "they have not waited that long")
    }

    // A sweeper whose stock check can be made to fail

    @Inject lateinit var updater: PrescriptionUpdater
    @Inject lateinit var inventory: InventoryRepository
    @Inject lateinit var transactions: Transactions
    @Inject lateinit var health: DatabaseHealth
    @Inject lateinit var clock: Clock
    @Inject lateinit var dataSource: DataSource

    private fun sweeperThat(every: Duration = Duration.ofSeconds(30), health: DatabaseHealth = this.health, transactions: Transactions = this.transactions, first: () -> Unit): StockCheckSweeper {
        val scripted = object : InventoryRepository by inventory {
            override fun lockForStockChecks() {
                first()
                inventory.lockForStockChecks()
            }
        }
        return StockCheckSweeper(false, every, Duration.ZERO, stalled, ReserveStock(updater, prescriptions, scripted, clock), transactions, health)
    }

    @Test
    fun `a stock check that keeps failing is not tried again at once, so it does not flood the log or the database`() {
        val maria = checkIn("ibuprofen", 1)
        val tries = AtomicInteger()
        val sweeper = sweeperThat { tries.incrementAndGet(); error("a bug") }

        assertEquals(0, sweeper.sweep())
        assertEquals(1, tries.get())
        assertEquals(0, sweeper.sweep(), "it failed a moment ago: it waits")
        assertEquals(0, sweeper.sweep())

        assertEquals(1, tries.get())
        assertEquals(RECEIVED, prescriptions.findById(maria.id)!!.status)
    }

    @Test
    fun `a stock check that failed is tried again once its pause is over, and then works`() {
        val maria = checkIn("ibuprofen", 1)
        val tries = AtomicInteger()
        val sweeper = sweeperThat(every = Duration.ofMillis(100)) { if (tries.incrementAndGet() == 1) error("a bug") }

        assertEquals(0, sweeper.sweep())
        Thread.sleep(250)
        assertEquals(1, sweeper.sweep())

        assertEquals(2, tries.get())
        assertEquals(AWAITING_APPROVAL, prescriptions.findById(maria.id)!!.status)
    }

    @Test
    fun `another instance getting there first is not a failure, and is not waited out`() {
        val maria = checkIn("ibuprofen", 1)
        val tries = AtomicInteger()
        val sweeper = sweeperThat { tries.incrementAndGet(); throw ConcurrentUpdateException(maria.ticketNumber) }

        assertEquals(0, sweeper.sweep())
        assertEquals(0, sweeper.sweep())

        assertEquals(2, tries.get(), "tried again at the next sweep: no pause for a lost race")
    }

    @Test
    fun `while the database is away the sweeper does not even try`() {
        checkIn("ibuprofen", 1)
        val database = ToggleDataSource(dataSource).also { it.down = true }
        val away = DatabaseHealth(database).also { it.failed(SQLException("Connection refused", "08001"), "test") }
        val tries = AtomicInteger()
        val sweeper = sweeperThat(health = away, transactions = Transactions(away)) { tries.incrementAndGet() }

        assertEquals(0, sweeper.sweep())

        assertEquals(0, tries.get())
        assertNotNull(sweeper.lastSweepAt, "it is alive, just waiting")
    }

    @Test
    fun `a sweep remembers when it last ran, for the liveness check`() {
        val sweeper = sweeperThat { }
        assertNull(sweeper.lastSweepAt)

        sweeper.sweep()

        assertNotNull(sweeper.lastSweepAt)
    }
}
