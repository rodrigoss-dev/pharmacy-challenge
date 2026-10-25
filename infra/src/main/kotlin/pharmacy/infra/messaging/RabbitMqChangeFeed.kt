package pharmacy.infra.messaging

import com.rabbitmq.client.Channel
import com.rabbitmq.client.Delivery
import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor
import jakarta.inject.Singleton
import org.jboss.logging.Logger
import pharmacy.domain.PrescriptionStatusChanged

/** Status changes from any instance of the system, as they happen. Best effort: events can be missed. */
interface PrescriptionChangeFeed {
    val changes: Multi<PrescriptionStatusChanged>
}

/**
 * Every app instance binds its own temporary queue to all prescription events, so every instance can push updates to
 * the patient screens connected to it, whichever instance made the change.
 */
@Singleton
class RabbitMqChangeFeed : SupervisedConsumer("Live updates feed"), PrescriptionChangeFeed {
    private val log = Logger.getLogger(javaClass)
    private val events = BroadcastProcessor.create<PrescriptionStatusChanged>()

    override val changes: Multi<PrescriptionStatusChanged> = events

    override val autoAck = true

    override fun prepare(channel: Channel): String {
        // Server-named, exclusive, deleted when the instance goes away. Bounded, so an instance that stops reading cannot
        // fill the broker: a live update older than a minute is worthless, the screens re-read the database anyway.
        val queue = channel.queueDeclare("", false, true, true, mapOf("x-message-ttl" to 60_000, "x-max-length" to 10_000)).queue
        channel.queueBind(queue, Topology.EVENTS_EXCHANGE, "prescription.#")
        return queue
    }

    override fun handle(channel: Channel, delivery: Delivery) {
        runCatching { StatusChangedMessages.decode(delivery.body) }
            .onSuccess {
                log.debugf("Change from the broker: #%d is now %s", it.ticketNumber, it.status)
                events.onNext(it)
            }
            .onFailure { log.warnf("Ignoring unreadable event (routing key %s): %s", delivery.envelope.routingKey, it.message) }
    }
}
