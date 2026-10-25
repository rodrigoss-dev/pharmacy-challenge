package pharmacy.infra.messaging

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Delivery
import com.rabbitmq.client.Envelope
import com.rabbitmq.client.ShutdownSignalException
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A consumer that keeps itself subscribed, against a broker that cancels it, closes its channel or drops it. */
class SupervisedConsumerTest {
    private class Probe(override val autoAck: Boolean = false) : SupervisedConsumer("Probe") {
        override val prefetch = 5
        val handled = CopyOnWriteArrayList<Long>()
        var failWith: Throwable? = null

        override fun prepare(channel: Channel) = "queue"

        override fun handle(channel: Channel, delivery: Delivery) {
            handled += delivery.envelope.deliveryTag
            failWith?.let { throw it }
        }
    }

    private val broker = FakeConnection()
    private val probe = Probe()

    private fun eventually(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError("Gave up waiting for $what")
            Thread.sleep(20)
        }
    }

    private fun deliver(consumer: Probe, channel: FakeChannel, tag: Long) = channel.consumer!!.handleDelivery("tag", Envelope(tag, false, "x", "y"), AMQP.BasicProperties(), ByteArray(0))

    private fun channelError() = ShutdownSignalException(false, false, AMQP.Channel.Close.Builder().replyCode(404).replyText("NOT_FOUND - no queue 'queue'").build(), null)

    @Test
    fun `subscribes to the queue the consumer asks for, a few deliveries at a time, and is active once the broker says so`() {
        probe.start(broker.connection)

        val channel = broker.channels.single()
        assertEquals(5, channel.qos)
        assertEquals("queue", channel.consumedQueue)
        assertEquals(false, channel.consumedWithAutoAck)
        assertFalse(probe.isActive, "not before the broker confirms the subscription")
        channel.consumer!!.handleConsumeOk("tag")
        assertTrue(probe.isActive)
    }

    @Test
    fun `hands the deliveries to the consumer`() {
        probe.start(broker.connection)

        deliver(probe, broker.channels.single(), 1)
        deliver(probe, broker.channels.single(), 2)

        assertEquals(listOf(1L, 2L), probe.handled)
    }

    @Test
    fun `a delivery that fails is given back to the queue as a failed one, and the consumer carries on`() {
        probe.failWith = IllegalStateException("a bug in the handler")
        probe.start(broker.connection)
        val channel = broker.channels.single()

        deliver(probe, channel, 1)
        probe.failWith = null
        deliver(probe, channel, 2)

        assertEquals(listOf(1L to true), channel.rejected, "rejected with requeue: it counts towards the delivery limit")
        assertEquals(listOf(1L, 2L), probe.handled)
    }

    @Test
    fun `with automatic acknowledgements there is nothing to give back`() {
        val live = Probe(autoAck = true).also { it.failWith = IllegalStateException("x") }
        live.start(broker.connection)

        deliver(live, broker.channels.single(), 1)

        assertTrue(broker.channels.single().rejected.isEmpty())
        assertEquals(true, broker.channels.single().consumedWithAutoAck)
    }

    @Test
    fun `an error that is not an exception is not hidden`() {
        probe.failWith = StackOverflowError()
        probe.start(broker.connection)

        assertFailsWith<StackOverflowError> { deliver(probe, broker.channels.single(), 1) }
        assertEquals(listOf(1L to true), broker.channels.single().rejected)
    }

    @Test
    fun `subscribes again when the broker cancels the subscription (its queue was deleted)`() {
        probe.start(broker.connection)
        val first = broker.channels.single()
        first.consumer!!.handleConsumeOk("tag")

        first.consumer!!.handleCancel("tag")

        assertFalse(probe.isActive)
        assertTrue(first.closed, "the old channel is closed")
        eventually("a new subscription") { broker.channels.size == 2 && broker.channels[1].consumer != null }
        assertEquals("queue", broker.channels[1].consumedQueue)
        broker.channels[1].consumer!!.handleConsumeOk("tag")
        assertTrue(probe.isActive)
    }

    @Test
    fun `subscribes again when the broker closes the channel on an error`() {
        probe.start(broker.connection)
        val first = broker.channels.single()
        first.consumer!!.handleConsumeOk("tag")

        first.consumer!!.handleShutdownSignal("tag", channelError())

        assertFalse(probe.isActive)
        eventually("a new subscription") { broker.channels.size == 2 && broker.channels[1].consumer != null }
    }

    @Test
    fun `keeps trying, with growing pauses, when it cannot subscribe again`() {
        probe.start(broker.connection)
        val first = broker.channels.single()
        broker.failToOpenChannels = 1 // the first attempt to subscribe again fails

        first.consumer!!.handleCancel("tag")

        eventually("a new subscription after the failed attempt", timeoutMs = 15_000) { broker.channels.size == 2 && broker.channels[1].consumer != null }
        assertEquals(3, broker.created.get(), "the first channel, the attempt that failed, and the one that worked")
    }

    @Test
    fun `leaves a lost connection to the client's own recovery, and a channel closed on purpose alone`() {
        probe.start(broker.connection)
        val first = broker.channels.single()
        first.consumer!!.handleConsumeOk("tag")

        first.consumer!!.handleShutdownSignal("tag", ShutdownSignalException(true, false, AMQP.Connection.Close.Builder().replyCode(320).replyText("CONNECTION_FORCED").build(), null))
        first.consumer!!.handleShutdownSignal("tag", ShutdownSignalException(false, true, AMQP.Channel.Close.Builder().replyCode(200).replyText("OK").build(), null))
        Thread.sleep(1_500)

        assertFalse(probe.isActive)
        assertEquals(1, broker.created.get(), "no second subscription from here")
    }

    @Test
    fun `stopping cancels the subscription and closes the channel, and does not subscribe again`() {
        probe.start(broker.connection)
        val first = broker.channels.single()
        first.consumer!!.handleConsumeOk("tag")

        probe.stop(Duration.ofMillis(200))
        first.consumer!!.handleShutdownSignal("tag", channelError()) // what closing it looks like to the consumer
        Thread.sleep(1_500)

        assertTrue(first.cancelled)
        assertTrue(first.closed)
        assertFalse(probe.isActive)
        assertEquals(1, broker.created.get())
    }

    @Test
    fun `cannot start when the broker refuses the subscription, and says so to whoever is connecting`() {
        broker.failToOpenChannels = 1

        assertFailsWith<java.io.IOException> { probe.start(broker.connection) }

        assertNull(broker.channels.firstOrNull())
    }
}
