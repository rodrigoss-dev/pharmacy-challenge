package pharmacy.infra.messaging

import com.rabbitmq.client.Channel
import com.rabbitmq.client.Delivery
import org.jboss.logging.Logger
import pharmacy.domain.ConcurrentUpdateException
import pharmacy.domain.NotFoundException
import pharmacy.domain.PrescriptionStatusChanged
import pharmacy.domain.usecase.ReserveStock
import pharmacy.infra.persistence.DatabaseFailure
import pharmacy.infra.persistence.DatabaseHealth
import pharmacy.infra.persistence.DatabaseUnavailableException
import pharmacy.infra.persistence.Transactions
import pharmacy.infra.persistence.pretty
import java.time.Duration

/**
 * Consumes `prescription.received` and runs the stock check, routing the prescription to the pharmacist queue.
 *
 * Failures are told apart, because the queue only redelivers a message [Topology.REDELIVERY_LIMIT] times before it parks it:
 * - database unreachable: the message is *held* until the database answers again, for at most [MAX_HOLD]; then it goes
 *   back to the queue, which does not count as a failed delivery, and the next one is held in turn. A restart or
 *   failover of the database therefore costs no delivery attempts, and nothing ends up in the dead-letter queue because
 *   of it. The other prefetched messages wait behind it, the rest stay in the queue.
 * - unreadable message, or unknown prescription: straight to the dead-letter queue, retrying cannot help.
 * - anything else: back to the queue after a pause, until the delivery limit parks it. The sweeper keeps retrying the
 *   prescription itself from the database, so a parked message never leaves a patient stuck.
 */
class StockCheckConsumer(
    private val reserveStock: ReserveStock,
    private val transactions: Transactions,
    private val database: DatabaseHealth,
    private val maxHold: Duration = MAX_HOLD,
    private val retryBackoff: Duration = RETRY_BACKOFF,
) : SupervisedConsumer("Stock check consumer") {
    private val log = Logger.getLogger(javaClass)

    override val prefetch = PREFETCH

    override fun prepare(channel: Channel): String {
        Topology.declare(channel)
        return Topology.STOCK_CHECK_QUEUE
    }

    /** Public so that tests can hand it deliveries without a broker. */
    public override fun handle(channel: Channel, delivery: Delivery) {
        val tag = delivery.envelope.deliveryTag
        val attempt = deliveryAttempt(delivery)
        val event = try {
            StatusChangedMessages.decode(delivery.body)
        } catch (e: Exception) {
            log.errorf(
                "Unreadable stock check message (routing key %s, %d bytes, delivery %d): %s. Moving it to the dead-letter queue. Body: %s",
                delivery.envelope.routingKey, delivery.body.size, attempt, e.message, delivery.body.preview(),
            )
            channel.basicReject(tag, false)
            return
        }
        val ticket = "#${event.ticketNumber}"
        val received = System.nanoTime()
        var conflicts = 0

        while (true) {
            val held = Duration.ofNanos(System.nanoTime() - received)
            if (!database.isAvailable && !holdUntilDatabaseIsBack(channel, tag, ticket, held)) return

            val started = System.nanoTime()
            try {
                val prescription = transactions.transaction("stock check for $ticket") { reserveStock(event.prescriptionId) }
                channel.basicAck(tag, false)
                val waited = Duration.ofNanos(started - received)
                log.infof(
                    "Stock check for %s done in %d ms: %s (delivery %d%s%s)",
                    ticket, Duration.ofNanos(System.nanoTime() - started).toMillis(), prescription.status, attempt,
                    if (delivery.envelope.isRedeliver) ", redelivered" else "",
                    if (waited > Duration.ofSeconds(1)) ", held ${waited.pretty()} for the database" else "",
                )
                return
            } catch (e: DatabaseUnavailableException) {
                // Unreachable: wait for it with the message in hand. Too slow or overloaded: an ordinary failure.
                if (e.failure == DatabaseFailure.UNAVAILABLE && !database.isAvailable) continue
                failed(channel, delivery, event, attempt, e)
                return
            } catch (e: NotFoundException) {
                log.errorf("Stock check for unknown prescription %s (%s): moving the message to the dead-letter queue", event.prescriptionId, ticket)
                channel.basicReject(tag, false)
                return
            } catch (e: ConcurrentUpdateException) {
                // A staff retry, the sweeper or another instance moved it at the same moment. Checking again finds it done.
                if (++conflicts < MAX_CONFLICTS) {
                    log.infof("Stock check for %s raced another change to it; checking again", ticket)
                    continue
                }
                failed(channel, delivery, event, attempt, e)
                return
            } catch (e: Exception) {
                failed(channel, delivery, event, attempt, e)
                return
            }
        }
    }

    /** Waits for the database with the message unacknowledged. False if it gave the message back to the queue instead. */
    private fun holdUntilDatabaseIsBack(channel: Channel, tag: Long, ticket: String, heldSoFar: Duration): Boolean {
        val budget = maxHold - heldSoFar
        log.infof("Stock check for %s waits for the database to come back; the message stays with this consumer meanwhile", ticket)
        if (budget > Duration.ZERO && database.awaitAvailable(budget) { !stopping }) {
            log.infof("Database is back: running the stock check for %s", ticket)
            return true
        }
        if (stopping) {
            log.infof("Shutting down: the stock check for %s goes back to the queue", ticket)
        } else {
            log.warnf(
                "Stock check for %s: the database is still unreachable after holding the message for %s; returning it to the queue",
                ticket, maxHold.pretty(),
            )
        }
        // Not a failed delivery: nack, which RabbitMQ 4 leaves out of the delivery limit (see Topology.REDELIVERY_LIMIT).
        channel.basicNack(tag, false, true)
        return false
    }

    private fun failed(channel: Channel, delivery: Delivery, event: PrescriptionStatusChanged, attempt: Int, e: Exception) {
        log.errorf(
            e,
            "Stock check for #%d failed (delivery %d). It goes back to the queue in %ss; once it has failed more than %d times RabbitMQ " +
                "parks it in %s, and the sweeper keeps retrying the prescription from the database",
            event.ticketNumber, attempt, retryBackoff.toSeconds(), Topology.REDELIVERY_LIMIT, Topology.STOCK_CHECK_DEAD_LETTERS,
        )
        Thread.sleep(retryBackoff) // crude backoff; holding up this consumer while things fail is fine
        // Reject, not nack: only this counts towards the delivery limit on every RabbitMQ version (see Topology.REDELIVERY_LIMIT).
        channel.basicReject(delivery.envelope.deliveryTag, true)
    }

    /** 1 for the first delivery. Quorum queues count failed deliveries in the `x-delivery-count` header. */
    private fun deliveryAttempt(delivery: Delivery): Int =
        ((delivery.properties.headers?.get("x-delivery-count") as? Number)?.toInt() ?: 0) + 1

    private fun ByteArray.preview(): String = String(this, Charsets.UTF_8).take(200).replace('\n', ' ')

    internal companion object {
        const val PREFETCH = 10
        const val MAX_CONFLICTS = 3
        val RETRY_BACKOFF: Duration = Duration.ofSeconds(1)

        /** How long RabbitMQ lets a delivery stay unacknowledged by default, before it closes the channel it went to. */
        val CONSUMER_TIMEOUT: Duration = Duration.ofMinutes(30)

        /**
         * How long one message is held while the database is away. Deliveries are handled one at a time, and up to
         * [PREFETCH] are in hand at once, all unacknowledged: the last of them has waited for every hold before its own.
         * So the holds add up, and [PREFETCH] of them must stay well under [CONSUMER_TIMEOUT], or RabbitMQ closes the
         * channel, which stops the consumer until it subscribes again.
         */
        val MAX_HOLD: Duration = Duration.ofMinutes(2)
    }
}
