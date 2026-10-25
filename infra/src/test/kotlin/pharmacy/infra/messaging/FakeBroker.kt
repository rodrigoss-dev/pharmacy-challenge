package pharmacy.infra.messaging

import com.rabbitmq.client.Channel
import com.rabbitmq.client.Connection
import com.rabbitmq.client.Consumer
import com.rabbitmq.client.ReturnCallback
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** An event the relay published. */
data class Published(val exchange: String, val routingKey: String, val mandatory: Boolean, val body: String)

/**
 * A RabbitMQ channel that records what the code under test does with it and answers like a broker that is fine, until
 * a test says otherwise. Only the calls the app makes are understood; any other answers with a default.
 */
class FakeChannel(
    /** What the broker answers when asked whether the events it was sent are confirmed. */
    @Volatile var confirms: Boolean = true,
) {
    val published = CopyOnWriteArrayList<Published>()
    val acked = CopyOnWriteArrayList<Long>()

    /** Delivery tag and whether it was requeued. */
    val rejected = CopyOnWriteArrayList<Pair<Long, Boolean>>()

    /** Delivery tag, multiple, requeue. */
    val nacked = CopyOnWriteArrayList<Triple<Long, Boolean, Boolean>>()

    @Volatile var qos: Int? = null

    @Volatile var consumedQueue: String? = null

    @Volatile var consumedWithAutoAck: Boolean? = null

    @Volatile var consumer: Consumer? = null

    @Volatile var returnListener: ReturnCallback? = null

    @Volatile var open = true

    @Volatile var aborted = false

    @Volatile var closed = false

    @Volatile var confirmSelected = false

    @Volatile var cancelled = false

    val channel: Channel = Proxy.newProxyInstance(Channel::class.java.classLoader, arrayOf(Channel::class.java)) { _, method, args ->
        when (method.name) {
            "isOpen" -> open
            "abort" -> { open = false; aborted = true; null }
            "close" -> { open = false; closed = true; null }
            "confirmSelect" -> { confirmSelected = true; null }
            "addReturnListener" -> { returnListener = args!![0] as? ReturnCallback; null }
            "waitForConfirms" -> confirms
            "basicQos" -> { qos = args!![0] as Int; null }
            "basicCancel" -> { cancelled = true; null }
            "basicAck" -> { acked += args!![0] as Long; null }
            "basicReject" -> { rejected += (args!![0] as Long) to (args[1] as Boolean); null }
            "basicNack" -> { nacked += Triple(args!![0] as Long, args[1] as Boolean, args[2] as Boolean); null }
            "basicPublish" -> {
                val values = args!!
                val body = values.last() as ByteArray
                published += Published(values[0] as String, values[1] as String, (values[2] as? Boolean) ?: false, String(body))
                null
            }
            "basicConsume" -> {
                val values = args!!
                consumedQueue = values[0] as String
                consumedWithAutoAck = values[1] as Boolean
                consumer = values.filterIsInstance<Consumer>().single()
                "consumer-tag"
            }
            "toString" -> "FakeChannel"
            "hashCode" -> System.identityHashCode(this)
            "equals" -> args!![0] === this
            else -> when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                else -> null
            }
        }
    } as Channel
}

/** A RabbitMQ connection that hands out channels, from the ones a test prepared first and new fine ones after. */
class FakeConnection(prepared: List<FakeChannel> = emptyList()) {
    private val spare = ArrayDeque(prepared)
    val channels = CopyOnWriteArrayList<FakeChannel>()
    val created = AtomicInteger()

    /** A test can make the next calls to open a channel fail. */
    @Volatile var failToOpenChannels = 0

    @Volatile var open = true

    val connection: Connection = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
        when (method.name) {
            "isOpen" -> open
            "createChannel" -> synchronized(this) {
                created.incrementAndGet()
                if (failToOpenChannels > 0) {
                    failToOpenChannels--
                    throw java.io.IOException("cannot open a channel")
                }
                (spare.removeFirstOrNull() ?: FakeChannel()).also { channels += it }.channel
            }
            "toString" -> "FakeConnection"
            "hashCode" -> System.identityHashCode(this)
            "equals" -> false
            else -> when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                Integer.TYPE -> 0
                else -> null
            }
        }
    } as Connection
}
