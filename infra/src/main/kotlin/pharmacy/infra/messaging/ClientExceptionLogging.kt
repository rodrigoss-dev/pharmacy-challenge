package pharmacy.infra.messaging

import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.Consumer
import com.rabbitmq.client.TopologyRecoveryException
import com.rabbitmq.client.impl.ForgivingExceptionHandler
import org.jboss.logging.Logger
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * The RabbitMQ client's own errors, in our log. Unlike the client's default handler, it never closes a channel because
 * a consumer threw (that would stop the consumer for good: channels closed by the application are not recovered).
 * Failed reconnection attempts, one every couple of seconds while the broker is down, are logged as a warning at most
 * every [REMIND_EVERY], and at TRACE in between (the lost connection itself is a warning of its own).
 */
internal class ClientExceptionLogging : ForgivingExceptionHandler() {
    private val log = Logger.getLogger(RabbitMqMessaging::class.java)
    private val lastRecoveryWarning = AtomicLong(System.nanoTime() - REMIND_EVERY.toNanos())

    override fun handleConnectionRecoveryException(conn: Connection, exception: Throwable) =
        throttled("Reconnecting %s to RabbitMQ failed: %s. Still trying every few seconds", conn, exception)

    /** Mostly a broker that accepts the connection while it boots, then resets it: part of reconnecting. */
    override fun handleUnexpectedConnectionDriverException(conn: Connection, exception: Throwable) =
        throttled("Unexpected error on RabbitMQ connection %s: %s", conn, exception)

    override fun handleTopologyRecoveryException(conn: Connection, ch: Channel?, exception: TopologyRecoveryException) =
        log.errorf(exception, "Reconnected %s to RabbitMQ, but could not declare the queues and bindings again: %s", conn.clientProvidedName, exception.message)

    override fun handleChannelRecoveryException(ch: Channel, exception: Throwable) =
        log.warnf("Could not reopen RabbitMQ channel %d after reconnecting: %s", ch.channelNumber, exception.describe())

    override fun handleConsumerException(channel: Channel, exception: Throwable, consumer: Consumer, consumerTag: String, methodName: String) =
        log.errorf(exception, "RabbitMQ consumer %s failed in %s (channel %d)", consumerTag, methodName, channel.channelNumber)

    override fun log(message: String, e: Throwable) = log.warnf(e, "RabbitMQ client: %s", message)

    /** A warning at most every [REMIND_EVERY]; the attempts in between at TRACE. */
    private fun throttled(format: String, conn: Connection, exception: Throwable) {
        val now = System.nanoTime()
        val last = lastRecoveryWarning.get()
        if (now - last >= REMIND_EVERY.toNanos() && lastRecoveryWarning.compareAndSet(last, now)) {
            log.warnf(format, conn.clientProvidedName, exception.describe())
        } else {
            log.tracef(format, conn.clientProvidedName, exception.describe())
        }
    }

    private companion object {
        val REMIND_EVERY: Duration = Duration.ofSeconds(30)
    }
}
