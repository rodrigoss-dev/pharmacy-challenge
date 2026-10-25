package pharmacy.infra.messaging

import com.rabbitmq.client.Connection
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import pharmacy.domain.MedicationId
import pharmacy.domain.PrescriptionStatus
import pharmacy.domain.usecase.SubmitPrescription
import pharmacy.infra.persistence.DatabaseHealth
import pharmacy.infra.persistence.JooqEventOutbox
import pharmacy.infra.persistence.ToggleDataSource
import pharmacy.infra.persistence.Transactions
import pharmacy.infra.reset
import java.sql.SQLException
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The relay between the outbox table (a real PostgreSQL) and the broker (a fake that confirms, or does not). */
@QuarkusTest
class OutboxRelayTest {
    @Inject lateinit var flyway: Flyway
    @Inject lateinit var submit: SubmitPrescription
    @Inject lateinit var outbox: JooqEventOutbox
    @Inject lateinit var transactions: Transactions
    @Inject lateinit var health: DatabaseHealth
    @Inject lateinit var dataSource: DataSource

    private val running = AtomicBoolean(true)
    private var worker: Thread? = null

    @BeforeEach
    fun fresh() {
        flyway.reset()
    }

    @AfterEach
    fun stopRelay() {
        running.set(false)
        worker?.join(10_000)
        assertTrue(health.awaitAvailable(Duration.ofSeconds(10)), "the shared database health is left as it was found")
    }

    private fun checkIn(packs: Int = 1) =
        submit(SubmitPrescription.Command(null, "Maria", listOf(SubmitPrescription.Command.Item(MedicationId("ibuprofen"), packs))))

    private fun backlog() = outbox.backlogDetails().events

    private fun start(
        connection: () -> Connection?,
        blockedReason: () -> String? = { null },
        transactions: Transactions = this.transactions,
        health: DatabaseHealth = this.health,
    ): OutboxRelay {
        val relay = OutboxRelay(outbox, transactions, health, connection, blockedReason)
        worker = Thread({ relay.run { running.get() } }, "test-outbox-relay").also { it.start() }
        return relay
    }

    private fun eventually(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("Gave up waiting for $what")
            Thread.sleep(20)
        }
    }

    @Test
    fun `publishes the events in the order they were written, to the events exchange, and removes them once confirmed`() {
        val first = checkIn()
        val second = checkIn(2)
        val broker = FakeConnection()

        start({ broker.connection })
        eventually("the outbox to be empty") { backlog() == 0 }

        val published = broker.channels.single().published
        assertEquals(listOf("prescription.received", "prescription.received"), published.map { it.routingKey })
        assertEquals(listOf("pharmacy.events"), published.map { it.exchange }.distinct())
        assertEquals(listOf(true), published.map { it.mandatory }.distinct(), "an event no queue takes comes back, not dropped")
        val events = published.map { StatusChangedMessages.decode(it.body.toByteArray()) }
        assertEquals(listOf(first.ticketNumber, second.ticketNumber), events.map { it.ticketNumber })
        assertEquals(listOf(first.id, second.id), events.map { it.prescriptionId })
        assertEquals(listOf(PrescriptionStatus.RECEIVED), events.map { it.status }.distinct())
        assertTrue(broker.channels.single().confirmSelected, "publisher confirms are on")
    }

    @Test
    fun `an event is picked up when it is written, without waiting`() {
        val broker = FakeConnection()
        val relay = start({ broker.connection })
        val running = relay.lastLoopAt
        eventually("the relay to go round once, finding nothing") { relay.lastLoopAt.isAfter(running) }
        assertEquals(0, broker.created.get(), "nothing to relay: no channel needed")

        checkIn()

        eventually("the event to be published") { broker.channels.sumOf { it.published.size } == 1 }
        eventually("the outbox to be empty") { backlog() == 0 }
    }

    @Test
    fun `an event the broker did not confirm stays in the outbox, and is published again on a fresh channel`() {
        checkIn()
        val refusing = FakeChannel(confirms = false)
        val broker = FakeConnection(prepared = listOf(refusing))

        start({ broker.connection })
        eventually("the second channel to get the event") { broker.channels.size == 2 && broker.channels[1].published.size == 1 }
        eventually("the outbox to be empty") { backlog() == 0 }

        assertEquals(1, refusing.published.size, "the refused attempt did go out")
        eventually("the doubtful channel to be abandoned") { refusing.aborted }
        assertEquals(1, broker.channels[1].published.size)
    }

    @Test
    fun `nothing is relayed while there is no connection to RabbitMQ, and everything is once there is`() {
        checkIn()
        checkIn()
        val broker = FakeConnection()
        val connection = AtomicReference<Connection?>(null)

        start({ connection.get() })
        Thread.sleep(600)
        assertEquals(2, backlog())
        assertEquals(0, broker.created.get())

        broker.open = false
        connection.set(broker.connection)
        Thread.sleep(600)
        assertEquals(2, backlog(), "a connection that is closed is no connection")

        broker.open = true
        eventually("the outbox to be empty") { backlog() == 0 }
        assertEquals(2, broker.channels.single().published.size)
    }

    @Test
    fun `nothing is relayed while RabbitMQ blocks publishers, and everything is once it stops`() {
        checkIn()
        val broker = FakeConnection()
        val blocked = AtomicReference<String?>("low on memory")

        start({ broker.connection }, blockedReason = { blocked.get() })
        Thread.sleep(600)
        assertEquals(1, backlog())
        assertEquals(0, broker.created.get(), "no channel was even opened: no transaction held open for a broker that will not answer")

        blocked.set(null)
        eventually("the outbox to be empty") { backlog() == 0 }
    }

    @Test
    fun `a broker that fails to open a channel is tried again`() {
        checkIn()
        val broker = FakeConnection().also { it.failToOpenChannels = 1 }

        start({ broker.connection })

        eventually("the outbox to be empty") { backlog() == 0 }
        assertEquals(2, broker.created.get(), "the first attempt to open a channel failed")
        assertEquals(1, broker.channels.single().published.size)
    }

    @Test
    fun `nothing is relayed while the database is away, and the relay carries on when it is back`() {
        checkIn()
        val broker = FakeConnection()
        val database = ToggleDataSource(dataSource).also { it.down = true }
        val away = DatabaseHealth(database).also { it.failed(SQLException("Connection refused", "08001"), "test") }

        val relay = start({ broker.connection }, transactions = Transactions(away), health = away)
        Thread.sleep(600)
        assertEquals(1, backlog())
        assertEquals(0, broker.created.get())

        database.down = false
        eventually("the outbox to be empty") { backlog() == 0 }
        assertTrue(away.isAvailable)
        assertNotNull(relay.lastLoopAt)
    }

    @Test
    fun `the liveness clock moves as long as the relay goes round`() {
        val broker = FakeConnection()
        val relay = start({ broker.connection })
        val first = relay.lastLoopAt

        eventually("the relay to go round again") { relay.lastLoopAt.isAfter(first) }
    }

    @Test
    fun `an event no queue took is not an error for the relay, whichever event it is`() {
        checkIn()
        val broker = FakeConnection()
        start({ broker.connection })
        eventually("the event to be published") { broker.channels.firstOrNull()?.returnListener != null }

        val returned = broker.channels.single().returnListener!!
        returned.handle(com.rabbitmq.client.Return(312, "NO_ROUTE", "pharmacy.events", "prescription.received", null, ByteArray(0)))
        returned.handle(com.rabbitmq.client.Return(312, "NO_ROUTE", "pharmacy.events", "prescription.called", null, ByteArray(0)))

        eventually("the outbox to be empty") { backlog() == 0 }
    }
}
