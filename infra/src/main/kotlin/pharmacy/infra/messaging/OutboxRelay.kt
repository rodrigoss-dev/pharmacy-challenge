package pharmacy.infra.messaging

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ReturnCallback
import org.jboss.logging.Logger
import pharmacy.domain.PrescriptionStatus
import pharmacy.infra.persistence.DatabaseHealth
import pharmacy.infra.persistence.DatabaseUnavailableException
import pharmacy.infra.persistence.JooqEventOutbox
import pharmacy.infra.persistence.Transactions
import pharmacy.infra.persistence.pretty
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

/**
 * Moves events from the outbox table to RabbitMQ, oldest first. A row is deleted only after the broker confirms it,
 * so events survive broker outages and app restarts. The price is at-least-once delivery: consumers are idempotent.
 *
 * It pauses, without hammering anything, while the database is unreachable, while RabbitMQ is, and while RabbitMQ
 * blocks publishing (memory or disk alarm). While paused with events waiting it logs a reminder every
 * [REMIND_EVERY]; when it gets going again it says so, and again once the backlog is delivered.
 */
class OutboxRelay(
    private val outbox: JooqEventOutbox,
    private val transactions: Transactions,
    private val database: DatabaseHealth,
    private val connection: () -> Connection?,
    private val blockedReason: () -> String?,
) {
    private val log = Logger.getLogger(javaClass)
    private var channel: Channel? = null

    /** Last time the loop went round: the liveness check fails if this stops moving. */
    @Volatile
    var lastLoopAt: Instant = Instant.now()
        private set

    /** What the relay is waiting for (or failing on) right now, since when, and the last reminder. */
    private var pause: String? = null
    private var pausedSince: Instant = Instant.now()
    private var lastReminder: Instant = Instant.EPOCH

    /** Set after a pause, until the backlog is empty again. */
    private var catchingUpAfter: String? = null
    private var deliveredWhileCatchingUp = 0

    /** Runs on its own thread until [isRunning] turns false or the thread is interrupted. */
    fun run(isRunning: () -> Boolean) {
        try {
            while (isRunning()) {
                lastLoopAt = Instant.now()
                val relayed = try {
                    relayOnce(isRunning)
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Throwable) {
                    if (!isRunning()) return
                    // The database outage is logged by DatabaseHealth; anything else is the broker's.
                    if (e !is DatabaseUnavailableException) paused("publishing to RabbitMQ fails", detail = e.relayFailure())
                    // Even abort() waits up to 10s for the broker's reply, which a blocked broker never sends: not here.
                    channel?.let { stale -> thread(name = "outbox-relay-abort", isDaemon = true) { runCatching { stale.abort() } } }
                    channel = null
                    if (e is Error && e !is OutOfMemoryError) throw e
                    Thread.sleep(RETRY_DELAY)
                    0
                }
                if (relayed == 0) Thread.sleep(POLL_INTERVAL)
            }
        } catch (_: InterruptedException) {
            // Shutting down.
        }
    }

    private fun relayOnce(isRunning: () -> Boolean): Int {
        if (!database.isAvailable) {
            paused("the database is unreachable")
            database.awaitAvailable(Duration.ofSeconds(5), isRunning)
            return 0
        }
        val connection = connection()?.takeIf { it.isOpen }
        if (connection == null) {
            paused("RabbitMQ is unreachable")
            return 0
        }
        blockedReason()?.let {
            paused("RabbitMQ is blocking publishers ($it)")
            return 0
        }

        val relayed = transactions.transaction("relaying events", slowAfter = CONFIRM_TIMEOUT) { relayBatch(connection) }
        resumed(relayed)
        return relayed
    }

    private fun relayBatch(connection: Connection): Int {
        val entries = outbox.lockOldest(BATCH_SIZE)
        if (entries.isEmpty()) return 0
        val channel = channel ?: connection.createChannel().also {
            it.confirmSelect()
            it.addReturnListener(ReturnCallback { returned -> unroutable(returned.routingKey, returned.replyCode, returned.replyText) })
            channel = it
        }
        entries.forEach {
            // Mandatory: an event no queue takes comes back (logged above) instead of being confirmed and dropped silently.
            channel.basicPublish(Topology.EVENTS_EXCHANGE, it.routingKey, true, PERSISTENT_JSON, it.payload.toByteArray())
        }
        // Not waitForConfirmsOrDie: on a timeout it closes the channel politely, which a broker that stopped reading (memory
        // alarm) never acknowledges, holding this transaction for the RPC timeout too. The caller aborts the channel instead.
        if (!channel.waitForConfirms(CONFIRM_TIMEOUT.toMillis())) throw IOException("RabbitMQ refused (nacked) some of the events")
        outbox.remove(entries.map { it.id })
        log.debugf(
            "Published %d event(s) (%s); the oldest waited %d ms",
            entries.size,
            entries.groupingBy { it.routingKey }.eachCount().entries.joinToString { (key, count) -> "${count}x $key" },
            Duration.between(entries.first().createdAt, Instant.now()).toMillis(),
        )
        return entries.size
    }

    private fun unroutable(routingKey: String, replyCode: Int, replyText: String) {
        if (routingKey == StatusChangedMessages.routingKey(PrescriptionStatus.RECEIVED)) {
            log.warnf(
                "RabbitMQ could not route %s (%d %s): the stock check queue is missing. The sweeper runs the stock check " +
                    "from the database, and the consumer declares the queue again when it subscribes",
                routingKey, replyCode, replyText,
            )
        } else {
            log.debugf("No queue took %s (%d %s): no instance is listening for live updates right now", routingKey, replyCode, replyText)
        }
    }

    /**
     * Records why the relay is not relaying, and reminds of the backlog while it lasts. Outages of the database or the
     * broker are logged where they are detected; a [detail] (a failure only the relay sees) is logged here, once.
     */
    private fun paused(reason: String, detail: String? = null) {
        val now = Instant.now()
        if (pause != reason) {
            if (detail != null) log.warnf("Outbox relay: %s (%s). Events stay in the outbox; retrying every %ss", reason, detail, RETRY_DELAY.toSeconds())
            pause = reason
            pausedSince = now
            lastReminder = if (detail != null) now else Instant.EPOCH
        } else if (detail != null) {
            log.debugf("Outbox relay: %s again (%s)", reason, detail)
        }
        val pausedFor = Duration.between(pausedSince, now)
        if (pausedFor < REMINDER_GRACE || Duration.between(lastReminder, now) < REMIND_EVERY) return
        lastReminder = now
        if (!database.isAvailable) return // nothing to count with, and DatabaseHealth already reminds
        val backlog = try {
            transactions.read("counting waiting events") { outbox.backlogDetails() }
        } catch (_: DatabaseUnavailableException) {
            return
        }
        if (backlog.events == 0) return
        log.warnf(
            "Outbox relay paused for %s (%s): %d event(s) waiting, the oldest %s old. Check-ins and staff actions keep working; " +
                "the sweeper runs stock checks from the database meanwhile",
            pausedFor.pretty(), reason, backlog.events, backlog.oldestAge()?.pretty(),
        )
    }

    private fun resumed(relayed: Int) {
        pause?.let { reason ->
            val pausedFor = Duration.between(pausedSince, Instant.now())
            // A blip (e.g. the first moments after startup, before the connection is up) is not worth a line.
            if (pausedFor >= REMINDER_GRACE || lastReminder != Instant.EPOCH) {
                log.infof("Outbox relay running again, after being paused for %s (cause: %s)", pausedFor.pretty(), reason)
            } else {
                log.debugf("Outbox relay running again, after being paused for %s (cause: %s)", pausedFor.pretty(), reason)
            }
            pause = null
            catchingUpAfter = reason
            deliveredWhileCatchingUp = 0
        }
        val after = catchingUpAfter ?: return
        deliveredWhileCatchingUp += relayed
        if (relayed == 0) {
            if (deliveredWhileCatchingUp > 0) {
                log.infof("Outbox drained: delivered the %d event(s) that waited during the pause (cause: %s)", deliveredWhileCatchingUp, after)
            }
            catchingUpAfter = null
        }
    }

    private fun Throwable.relayFailure(): String =
        if (generateSequence(this) { it.cause }.take(10).any { it is TimeoutException }) {
            "no confirmation within ${CONFIRM_TIMEOUT.toSeconds()}s"
        } else {
            describe()
        }

    private companion object {
        const val BATCH_SIZE = 100
        val POLL_INTERVAL: Duration = Duration.ofMillis(100)
        val RETRY_DELAY: Duration = Duration.ofSeconds(2)
        val CONFIRM_TIMEOUT: Duration = Duration.ofSeconds(5)
        val REMIND_EVERY: Duration = Duration.ofSeconds(30)

        /** Do not remind about the first seconds of a pause, e.g. while the app connects at startup. */
        val REMINDER_GRACE: Duration = Duration.ofSeconds(5)
        val PERSISTENT_JSON: AMQP.BasicProperties = AMQP.BasicProperties.Builder()
            .contentType("application/json")
            .deliveryMode(2)
            .appId("greenleaf-pharmacy")
            .build()
    }
}
