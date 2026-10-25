package pharmacy.infra.messaging

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.DefaultConsumer
import com.rabbitmq.client.Delivery
import com.rabbitmq.client.Envelope
import com.rabbitmq.client.ShutdownSignalException
import org.jboss.logging.Logger
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A RabbitMQ consumer that keeps itself subscribed.
 *
 * The client's automatic recovery subscribes consumers again after a lost *connection*, but not after a channel-level
 * error (the broker closes just this channel) or when the broker cancels the subscription (its queue was deleted).
 * Either would silently stop the consumer while the connection, and the health check, look fine. This subscribes
 * again, with backoff, until it works or the app stops. Exceptions from [handle] are logged and never reach the client.
 */
abstract class SupervisedConsumer(private val name: String) {
    private val log = Logger.getLogger(javaClass)
    private val resubscribing = AtomicBoolean(false)
    private val inFlight = AtomicInteger(0)

    @Volatile
    private var connection: Connection? = null

    @Volatile
    private var subscription: Subscription? = null

    /** Set while the app shuts down: deliveries being held (see [StockCheckConsumer]) go back to the queue. */
    @Volatile
    protected var stopping = false
        private set

    /** Subscribed and receiving deliveries. False while reconnecting, or after a failure until subscribed again. */
    @Volatile
    var isActive = false
        private set

    /** How many unacknowledged deliveries the broker may push at once; 0 for no limit. */
    protected open val prefetch: Int = 0

    protected open val autoAck: Boolean = false

    /** Declares what this consumer needs on [channel], and returns the queue to consume from. */
    protected abstract fun prepare(channel: Channel): String

    protected abstract fun handle(channel: Channel, delivery: Delivery)

    /** Subscribes on [connection]. Throws if it cannot: the caller is connecting, and retries as a whole. */
    fun start(connection: Connection) {
        this.connection = connection
        stopping = false
        subscribe(connection)
    }

    /** Stops taking deliveries, waits up to [grace] for the one being handled, and closes the channel. */
    fun stop(grace: Duration) {
        stopping = true
        val subscription = subscription ?: return
        val channel = subscription.channel
        runCatching { if (channel.isOpen) channel.basicCancel(subscription.consumerTag) }
        val deadline = System.nanoTime() + grace.toNanos()
        while (inFlight.get() > 0 && System.nanoTime() < deadline) Thread.sleep(50)
        if (inFlight.get() > 0) log.warnf("%s: still busy with a message after %ss; the broker will redeliver it", name, grace.toSeconds())
        runCatching { if (channel.isOpen) channel.close() } // unacknowledged deliveries go back to the queue
        isActive = false
    }

    private fun subscribe(connection: Connection) {
        val channel = connection.createChannel()
        try {
            if (prefetch > 0) channel.basicQos(prefetch)
            val queue = prepare(channel)
            subscription = Subscription(channel, queue).also { channel.basicConsume(queue, autoAck, it) }
        } catch (e: Exception) {
            runCatching { channel.abort() }
            throw e
        }
    }

    private fun resubscribeLater(reason: String) {
        if (stopping || !resubscribing.compareAndSet(false, true)) return
        thread(name = "$name resubscribe", isDaemon = true) {
            var wait = Duration.ofSeconds(1)
            try {
                while (!stopping) {
                    Thread.sleep(wait)
                    val connection = connection ?: break
                    try {
                        subscribe(connection)
                        log.infof("%s: subscribed again %s", name, reason)
                        break
                    } catch (e: Exception) {
                        wait = minOf(wait.multipliedBy(2), MAX_RESUBSCRIBE_WAIT)
                        log.warnf("%s: could not subscribe again (%s), retrying in %ss", name, e.describe(), wait.toSeconds())
                    }
                }
            } catch (_: InterruptedException) {
                // Shutting down.
            } finally {
                resubscribing.set(false)
            }
        }
    }

    private inner class Subscription(channel: Channel, private val queue: String) : DefaultConsumer(channel) {
        private var subscribedBefore = false

        override fun handleConsumeOk(consumerTag: String) {
            super.handleConsumeOk(consumerTag)
            isActive = true
            if (subscribedBefore) log.infof("%s: consuming again after reconnecting", name) else log.infof("%s: consuming from %s", name, queue)
            subscribedBefore = true
        }

        override fun handleDelivery(consumerTag: String, envelope: Envelope, properties: AMQP.BasicProperties, body: ByteArray) {
            inFlight.incrementAndGet()
            try {
                handle(channel, Delivery(envelope, properties, body))
            } catch (e: Throwable) {
                log.errorf(e, "%s: unexpected failure handling delivery %d (routing key %s)", name, envelope.deliveryTag, envelope.routingKey)
                if (!autoAck) runCatching { channel.basicReject(envelope.deliveryTag, true) } // counts towards the delivery limit
                if (e is Error) throw e
            } finally {
                inFlight.decrementAndGet()
            }
        }

        override fun handleCancel(consumerTag: String) {
            isActive = false
            log.warnf("%s: the broker cancelled the subscription to %s (queue deleted, or its node went away). Subscribing again", name, queue)
            runCatching { channel.close() }
            resubscribeLater("after the broker cancelled it")
        }

        override fun handleShutdownSignal(consumerTag: String, sig: ShutdownSignalException) {
            isActive = false
            when {
                stopping || sig.isInitiatedByApplication -> log.debugf("%s: channel closed", name)
                // The client reconnects and subscribes this consumer again by itself; the connection logs it.
                sig.isHardError -> log.debugf("%s: connection lost (%s)", name, sig.describe())
                else -> {
                    log.warnf("%s: the broker closed the channel (%s). Subscribing again", name, sig.describe())
                    resubscribeLater("after a channel error")
                }
            }
        }
    }

    private companion object {
        val MAX_RESUBSCRIBE_WAIT: Duration = Duration.ofSeconds(30)
    }
}

/** e.g. `406 PRECONDITION_FAILED - inequivalent arg 'x-delivery-limit'...`, or `connection error (SocketException: Connection reset)`. */
internal fun ShutdownSignalException.describe(): String {
    val reason = when (val method = reason) {
        is AMQP.Channel.Close -> "${method.replyCode} ${method.replyText}"
        is AMQP.Connection.Close -> "${method.replyCode} ${method.replyText}"
        else -> if (isHardError) "connection error" else "channel error"
    }
    return reason + (cause?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: "")
}

/** A one-line description of a broker failure, looking through the wrapping IOException the client throws. */
internal fun Throwable.describe(): String {
    val signal = generateSequence(this) { it.cause }.take(10).filterIsInstance<ShutdownSignalException>().firstOrNull()
    return signal?.describe() ?: "${javaClass.simpleName}: ${message ?: cause?.message ?: "no details"}"
}
