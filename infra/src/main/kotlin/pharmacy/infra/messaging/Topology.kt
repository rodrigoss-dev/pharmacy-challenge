package pharmacy.infra.messaging

import com.rabbitmq.client.BuiltinExchangeType
import com.rabbitmq.client.Channel
import pharmacy.domain.PrescriptionStatus

/**
 * pharmacy.events (topic) --prescription.received--> pharmacy.stock-check (quorum) --once REDELIVERY_LIMIT is used up-->
 * pharmacy.dead-letter (direct) --> pharmacy.stock-check.dead-letter
 *
 * pharmacy.events (topic) --prescription.#--> one temporary queue per app instance (live screens)
 */
object Topology {
    const val EVENTS_EXCHANGE = "pharmacy.events"
    const val DEAD_LETTER_EXCHANGE = "pharmacy.dead-letter"
    const val STOCK_CHECK_QUEUE = "pharmacy.stock-check"
    const val STOCK_CHECK_DEAD_LETTERS = "pharmacy.stock-check.dead-letter"

    /**
     * How many times RabbitMQ delivers a failed message again before it parks it: the 6th failed delivery is the last.
     * Only a message given back with `basic.reject(requeue)` counts. RabbitMQ 4 does not count `basic.nack(requeue)`,
     * so that one never reaches the limit, however often it fails.
     */
    const val REDELIVERY_LIMIT = 5

    fun declare(channel: Channel) {
        channel.exchangeDeclare(EVENTS_EXCHANGE, BuiltinExchangeType.TOPIC, true)
        channel.exchangeDeclare(DEAD_LETTER_EXCHANGE, BuiltinExchangeType.DIRECT, true)

        channel.queueDeclare(STOCK_CHECK_DEAD_LETTERS, true, false, false, mapOf("x-queue-type" to "quorum"))
        channel.queueBind(STOCK_CHECK_DEAD_LETTERS, DEAD_LETTER_EXCHANGE, STOCK_CHECK_QUEUE)

        // Quorum queue: replicated, and counts redeliveries so a message that keeps failing is parked, not retried forever.
        channel.queueDeclare(
            STOCK_CHECK_QUEUE, true, false, false,
            mapOf(
                "x-queue-type" to "quorum",
                "x-delivery-limit" to REDELIVERY_LIMIT,
                "x-dead-letter-exchange" to DEAD_LETTER_EXCHANGE,
                "x-dead-letter-routing-key" to STOCK_CHECK_QUEUE,
            ),
        )
        channel.queueBind(STOCK_CHECK_QUEUE, EVENTS_EXCHANGE, StatusChangedMessages.routingKey(PrescriptionStatus.RECEIVED))
    }
}
