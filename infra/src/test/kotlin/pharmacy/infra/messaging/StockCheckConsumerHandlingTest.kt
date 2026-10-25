package pharmacy.infra.messaging

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Delivery
import com.rabbitmq.client.Envelope
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import pharmacy.domain.ConcurrentUpdateException
import pharmacy.domain.MedicationId
import pharmacy.domain.Prescription
import pharmacy.domain.PrescriptionId
import pharmacy.domain.PrescriptionStatus
import pharmacy.domain.PrescriptionStatus.AWAITING_APPROVAL
import pharmacy.domain.PrescriptionStatus.RECEIVED
import pharmacy.domain.PrescriptionStatusChanged
import pharmacy.domain.port.InventoryRepository
import pharmacy.domain.port.PrescriptionRepository
import pharmacy.domain.usecase.PrescriptionUpdater
import pharmacy.domain.usecase.ReserveStock
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
import kotlin.test.assertTrue

/**
 * What the stock check consumer does with each delivery, and how it tells the broker (ack, reject, nack): the broker
 * is a fake that records it, the database a real PostgreSQL. The rest of the system around it is the real one.
 */
@QuarkusTest
class StockCheckConsumerHandlingTest {
    @Inject lateinit var flyway: Flyway
    @Inject lateinit var submit: SubmitPrescription
    @Inject lateinit var reserveStock: ReserveStock
    @Inject lateinit var updater: PrescriptionUpdater
    @Inject lateinit var prescriptions: PrescriptionRepository
    @Inject lateinit var inventory: InventoryRepository
    @Inject lateinit var transactions: Transactions
    @Inject lateinit var health: DatabaseHealth
    @Inject lateinit var clock: Clock
    @Inject lateinit var dataSource: DataSource

    private val channel = FakeChannel()

    @BeforeEach
    fun fresh() {
        flyway.reset()
    }

    private fun checkIn(medication: String = "ibuprofen", packs: Int = 1): Prescription =
        submit(SubmitPrescription.Command(null, "Maria", listOf(SubmitPrescription.Command.Item(MedicationId(medication), packs))))

    private fun stock() = inventory.findByIds(listOf(MedicationId("ibuprofen"))).single().stock

    private fun status(p: Prescription): PrescriptionStatus = prescriptions.findById(p.id)!!.status

    private fun delivery(body: ByteArray, tag: Long = 7, redelivered: Boolean = false, deliveryCount: Int? = null) = Delivery(
        Envelope(tag, redelivered, "pharmacy.events", "prescription.received"),
        AMQP.BasicProperties.Builder().headers(deliveryCount?.let { mapOf<String, Any>("x-delivery-count" to it) }).build(),
        body,
    )

    private fun received(p: Prescription, tag: Long = 7, redelivered: Boolean = false, deliveryCount: Int? = null) =
        delivery(StatusChangedMessages.encode(PrescriptionStatusChanged.of(p)).toByteArray(), tag, redelivered, deliveryCount)

    private fun consumer(
        stockCheck: ReserveStock = reserveStock,
        transactions: Transactions = this.transactions,
        health: DatabaseHealth = this.health,
        maxHold: Duration = Duration.ofSeconds(5),
    ) = StockCheckConsumer(stockCheck, transactions, health, maxHold = maxHold, retryBackoff = Duration.ofMillis(10))

    /** A stock check whose lock - the first thing it does in the database - runs [first] before it carries on. */
    private fun stockCheckThat(first: () -> Unit): ReserveStock {
        val scripted = object : InventoryRepository by inventory {
            override fun lockForStockChecks() {
                first()
                inventory.lockForStockChecks()
            }
        }
        return ReserveStock(updater, prescriptions, scripted, clock)
    }

    @Test
    fun `a stock check message does the stock check, and is acknowledged once it is saved`() {
        val maria = checkIn(packs = 2)

        consumer().handle(channel.channel, received(maria))

        assertEquals(AWAITING_APPROVAL, status(maria))
        assertEquals(58, stock())
        assertEquals(listOf(7L), channel.acked)
        assertTrue(channel.rejected.isEmpty() && channel.nacked.isEmpty())
    }

    @Test
    fun `a message delivered again is acknowledged and changes nothing`() {
        val maria = checkIn(packs = 2)
        consumer().handle(channel.channel, received(maria, tag = 1))

        consumer().handle(channel.channel, received(maria, tag = 2, redelivered = true, deliveryCount = 1))

        assertEquals(58, stock(), "reserved once")
        assertEquals(listOf(1L, 2L), channel.acked)
    }

    @Test
    fun `a message nobody can read goes straight to the dead-letter queue`() {
        consumer().handle(channel.channel, delivery("not an event".toByteArray()))
        consumer().handle(channel.channel, delivery("""{"eventId":"x"}""".toByteArray(), tag = 8))

        assertEquals(listOf(7L to false, 8L to false), channel.rejected, "rejected without requeue")
        assertTrue(channel.acked.isEmpty() && channel.nacked.isEmpty())
    }

    @Test
    fun `a message for a prescription that does not exist goes straight to the dead-letter queue`() {
        val ghost = checkIn().copy(id = PrescriptionId.random())

        consumer().handle(channel.channel, received(ghost))

        assertEquals(listOf(7L to false), channel.rejected)
        assertTrue(channel.acked.isEmpty())
    }

    @Test
    fun `a stock check that raced another change is checked again`() {
        val maria = checkIn()
        val races = AtomicInteger()
        val twice = stockCheckThat { if (races.incrementAndGet() <= 2) throw ConcurrentUpdateException(maria.ticketNumber) }

        consumer(stockCheck = twice).handle(channel.channel, received(maria))

        assertEquals(3, races.get())
        assertEquals(AWAITING_APPROVAL, status(maria))
        assertEquals(listOf(7L), channel.acked)
    }

    @Test
    fun `a stock check that keeps racing goes back to the queue, counting as a failed delivery`() {
        val maria = checkIn()
        val races = AtomicInteger()
        val always = stockCheckThat { races.incrementAndGet(); throw ConcurrentUpdateException(maria.ticketNumber) }

        consumer(stockCheck = always).handle(channel.channel, received(maria))

        assertEquals(StockCheckConsumer.MAX_CONFLICTS, races.get())
        assertEquals(RECEIVED, status(maria))
        assertEquals(listOf(7L to true), channel.rejected, "rejected with requeue: it counts towards the delivery limit")
        assertTrue(channel.acked.isEmpty() && channel.nacked.isEmpty())
    }

    @Test
    fun `any other failure goes back to the queue after a pause, as a failed delivery`() {
        val maria = checkIn()
        val broken = stockCheckThat { error("a bug") }

        consumer(stockCheck = broken).handle(channel.channel, received(maria, deliveryCount = 3))

        assertEquals(listOf(7L to true), channel.rejected)
        assertTrue(channel.acked.isEmpty() && channel.nacked.isEmpty())
        assertEquals(RECEIVED, status(maria))
        assertEquals(60, stock(), "nothing was reserved")
    }

    @Test
    fun `a statement that timed out is an ordinary failure, not a database outage to wait out`() {
        val maria = checkIn()
        val slow = stockCheckThat { throw SQLException("canceling statement due to statement timeout", "57014") }

        consumer(stockCheck = slow).handle(channel.channel, received(maria))

        assertEquals(listOf(7L to true), channel.rejected)
        assertTrue(channel.nacked.isEmpty())
    }

    @Test
    fun `while the database is away the message is held, then the stock check is done as soon as it is back`() {
        val maria = checkIn()
        val database = ToggleDataSource(dataSource).also { it.down = true }
        val away = DatabaseHealth(database).also { it.failed(SQLException("Connection refused", "08001"), "test") }
        val consumer = consumer(transactions = Transactions(away), health = away, maxHold = Duration.ofSeconds(20))

        val handling = Thread { consumer.handle(channel.channel, received(maria)) }.also { it.start() }
        Thread.sleep(500)
        assertTrue(channel.acked.isEmpty() && channel.rejected.isEmpty() && channel.nacked.isEmpty(), "held: neither acknowledged nor given back")
        assertEquals(RECEIVED, status(maria))
        database.down = false
        handling.join(15_000)

        assertEquals(AWAITING_APPROVAL, status(maria))
        assertEquals(listOf(7L), channel.acked)
        assertTrue(channel.rejected.isEmpty() && channel.nacked.isEmpty(), "no delivery attempt was used up")
        assertTrue(away.isAvailable)
    }

    @Test
    fun `when the database stays away the message goes back to the queue without counting as a failure`() {
        val maria = checkIn()
        val database = ToggleDataSource(dataSource).also { it.down = true }
        val away = DatabaseHealth(database).also { it.failed(SQLException("Connection refused", "08001"), "test") }
        val consumer = consumer(transactions = Transactions(away), health = away, maxHold = Duration.ofMillis(600))

        consumer.handle(channel.channel, received(maria))

        assertEquals(listOf(Triple(7L, false, true)), channel.nacked, "nack with requeue: RabbitMQ does not count it")
        assertTrue(channel.acked.isEmpty() && channel.rejected.isEmpty())
        assertEquals(RECEIVED, status(maria))
    }

    @Test
    fun `the consumer takes ten deliveries at a time, from the stock check queue`() {
        val connection = FakeConnection()
        val consumer = consumer()

        consumer.start(connection.connection)

        val subscribed = connection.channels.single()
        assertEquals(10, subscribed.qos)
        assertEquals("pharmacy.stock-check", subscribed.consumedQueue)
        assertEquals(false, subscribed.consumedWithAutoAck, "acknowledged by hand, once the stock check is saved")
        consumer.stop(Duration.ofMillis(100))
    }
}
