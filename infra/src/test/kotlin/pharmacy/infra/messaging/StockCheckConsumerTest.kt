package pharmacy.infra.messaging

import pharmacy.infra.persistence.pretty
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Holding a message while the database is away is only safe if the messages queued behind it do not outwait RabbitMQ.
 * The consumer cannot be driven without a broker, so this guards the numbers it relies on.
 */
class StockCheckConsumerTest {
    @Test
    fun `holding every message in hand in turn stays well within RabbitMQ's consumer timeout`() {
        // The last of the prefetched messages waits for the hold of each one before it, then for its own.
        val worstCase = StockCheckConsumer.MAX_HOLD.multipliedBy(StockCheckConsumer.PREFETCH.toLong())
        val limit = StockCheckConsumer.CONSUMER_TIMEOUT.minusMinutes(5)

        assertTrue(
            worstCase <= limit,
            "The last of ${StockCheckConsumer.PREFETCH} prefetched messages could wait ${worstCase.pretty()} unacknowledged, " +
                "but RabbitMQ closes the channel after ${StockCheckConsumer.CONSUMER_TIMEOUT.pretty()} (and the work needs room): " +
                "shorten MAX_HOLD or the prefetch",
        )
    }
}
