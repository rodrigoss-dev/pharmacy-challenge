package pharmacy.infra.messaging

import com.rabbitmq.client.AMQP
import com.rabbitmq.client.BlockedListener
import com.rabbitmq.client.Connection
import com.rabbitmq.client.ConnectionFactory
import com.rabbitmq.client.Recoverable
import com.rabbitmq.client.RecoveryListener
import com.rabbitmq.client.ShutdownSignalException
import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.event.Observes
import jakarta.inject.Singleton
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import pharmacy.domain.usecase.ReserveStock
import pharmacy.infra.persistence.DatabaseHealth
import pharmacy.infra.persistence.JooqEventOutbox
import pharmacy.infra.persistence.Transactions
import java.net.URI
import java.time.Duration
import java.time.Instant
import kotlin.concurrent.thread

/** What the broker looks like from this instance, for the health checks and the staff console. */
data class BrokerStatus(
    val enabled: Boolean,
    val publisherConnected: Boolean,
    val consumerConnected: Boolean,
    val stockCheckConsumerActive: Boolean,
    val liveUpdatesFeedActive: Boolean,
    /** Why RabbitMQ blocks publishing (memory or disk alarm); null when it does not. */
    val blocked: String?,
    /** Messages parked in the stock check dead-letter queue; null until the broker has been asked. */
    val deadLetters: Long?,
    /** Stock check messages waiting in the queue; null until the broker has been asked. */
    val stockChecksQueued: Long?,
) {
    val isUp: Boolean get() = publisherConnected && consumerConnected
}

/**
 * Owns the RabbitMQ connections, the outbox relay and the consumers.
 *
 * The app does not need the broker to start or to take check-ins: events wait in the outbox until it is reachable.
 * After the first connection, the client's automatic recovery reconnects and subscribes the consumers again;
 * [SupervisedConsumer] covers what recovery does not.
 *
 * Publishing and consuming use separate connections. When RabbitMQ runs low on memory or disk it blocks the connections
 * that publish, which would also stop the consumers' acknowledgements on a shared connection: the stock check queue
 * could not drain, prolonging the alarm. While blocked, the relay pauses instead of holding a database transaction open.
 */
@Singleton
class RabbitMqMessaging(
    @ConfigProperty(name = "pharmacy.rabbitmq.uri") private val uri: String,
    @ConfigProperty(name = "pharmacy.rabbitmq.enabled") private val enabled: Boolean,
    outbox: JooqEventOutbox,
    transactions: Transactions,
    database: DatabaseHealth,
    reserveStock: ReserveStock,
    private val changeFeed: RabbitMqChangeFeed,
) {
    private val log = Logger.getLogger(javaClass)
    private val factory = ConnectionFactory().apply {
        setUri(uri)
        isAutomaticRecoveryEnabled = true
        networkRecoveryInterval = RECONNECT_EVERY.toMillis()
        requestedHeartbeat = HEARTBEAT.toSeconds().toInt()
        connectionTimeout = 5_000
        handshakeTimeout = 5_000
        // The default is 10 minutes: a call on a half-dead connection would hold its thread that long.
        channelRpcTimeout = 10_000
        exceptionHandler = ClientExceptionLogging()
    }
    private val stockChecks = StockCheckConsumer(reserveStock, transactions, database)
    private val relay = OutboxRelay(outbox, transactions, database, { publisher }, { blockedReason })
    private val workers = mutableListOf<Thread>()

    @Volatile
    private var running = false

    @Volatile
    private var publisher: Connection? = null

    @Volatile
    private var consumer: Connection? = null

    @Volatile
    private var blockedReason: String? = null

    @Volatile
    private var deadLetters: Long? = null

    @Volatile
    private var stockChecksQueued: Long? = null

    val status: BrokerStatus
        get() = BrokerStatus(
            enabled = enabled,
            publisherConnected = publisher?.isOpen == true,
            consumerConnected = consumer?.isOpen == true,
            stockCheckConsumerActive = stockChecks.isActive,
            liveUpdatesFeedActive = changeFeed.isActive,
            blocked = blockedReason,
            deadLetters = deadLetters.takeIf { consumer?.isOpen == true },
            stockChecksQueued = stockChecksQueued.takeIf { consumer?.isOpen == true },
        )

    /** When the outbox relay last went round its loop; null if messaging is disabled. */
    val relayLastLoopAt: Instant? get() = if (running) relay.lastLoopAt else null

    fun start(@Observes event: StartupEvent) {
        if (!enabled) {
            log.warn("RabbitMQ is disabled (pharmacy.rabbitmq.enabled=false): events stay in the outbox")
            return
        }
        log.infof(
            "RabbitMQ at %s: separate publisher and consumer connections, heartbeat every %ss, reconnecting every %ss when lost",
            uri.withoutPassword(), HEARTBEAT.toSeconds(), RECONNECT_EVERY.toSeconds(),
        )
        running = true
        workers += thread(name = "rabbitmq-connect", isDaemon = true) { connectWithRetry() }
        workers += thread(name = "outbox-relay", isDaemon = true) { relay.run { running } }
        workers += thread(name = "rabbitmq-monitor", isDaemon = true) { monitor() }
    }

    fun stop(@Observes event: ShutdownEvent) {
        if (!running) return
        log.info("Stopping messaging: taking no new deliveries, finishing the stock check in progress")
        running = false
        stockChecks.stop(SHUTDOWN_GRACE)
        changeFeed.stop(SHUTDOWN_GRACE)
        workers.forEach { it.interrupt() }
        workers.forEach { it.join(SHUTDOWN_GRACE.toMillis()) }
        listOfNotNull(consumer, publisher).forEach { runCatching { it.close(SHUTDOWN_GRACE.toMillis().toInt()) } }
        log.info("Messaging stopped. Events not yet relayed stay in the outbox for the next instance")
    }

    private fun connectWithRetry() {
        var wait = Duration.ofSeconds(1)
        var attempt = 1
        try {
            while (running) {
                try {
                    connect()
                    return
                } catch (e: Exception) {
                    if (e is InterruptedException) throw e
                    if (e.isPreconditionFailed()) {
                        log.errorf(
                            "RabbitMQ refused the topology this app declares: %s. A queue or exchange already exists with other " +
                                "settings than Topology.kt; delete it (the app declares it again) or align the code. Retrying in %ss",
                            e.describe(), wait.toSeconds(),
                        )
                    } else {
                        log.warnf(
                            "RabbitMQ not reachable (attempt %d: %s), retrying in %ss. Check-ins keep working; events wait in the outbox",
                            attempt, e.describe(), wait.toSeconds(),
                        )
                    }
                    Thread.sleep(wait)
                    wait = minOf(wait.multipliedBy(2), MAX_RETRY_WAIT)
                    attempt++
                }
            }
        } catch (_: InterruptedException) {
            // Shutting down.
        }
    }

    private fun connect() {
        val publisher = factory.newConnection("pharmacy-publisher")
        val consumer = try {
            factory.newConnection("pharmacy-consumer")
        } catch (e: Exception) {
            publisher.abort()
            throw e
        }
        try {
            stockChecks.start(consumer) // declares the topology first
            changeFeed.start(consumer)
        } catch (e: Exception) {
            consumer.abort()
            publisher.abort()
            throw e
        }
        watch(publisher, "publisher")
        watch(consumer, "consumer")
        this.publisher = publisher
        this.consumer = consumer
        val server = publisher.serverProperties
        log.infof("Connected to RabbitMQ %s (cluster %s) at %s:%d", server["version"], server["cluster_name"], factory.host, publisher.port)
    }

    private fun watch(connection: Connection, role: String) {
        connection.addShutdownListener { signal: ShutdownSignalException ->
            if (!signal.isInitiatedByApplication) {
                log.warnf(
                    "Lost the %s connection to RabbitMQ: %s. Reconnecting every %ss%s",
                    role, signal.describe(), RECONNECT_EVERY.toSeconds(),
                    if (role == "publisher") "; events wait in the outbox meanwhile" else "; stock checks fall back to the sweeper meanwhile",
                )
            }
        }
        (connection as Recoverable).addRecoveryListener(object : RecoveryListener {
            override fun handleRecoveryStarted(recoverable: Recoverable) = log.infof("Reconnecting the %s connection to RabbitMQ...", role)
            override fun handleRecovery(recoverable: Recoverable) = log.infof("Reconnected the %s connection to RabbitMQ", role)
        })
        connection.addBlockedListener(object : BlockedListener {
            override fun handleBlocked(reason: String) {
                if (role == "publisher") blockedReason = reason
                log.warnf(
                    "RabbitMQ blocked the %s connection: %s. It is out of memory or disk space; events wait in the outbox until it recovers",
                    role, reason,
                )
            }

            override fun handleUnblocked() {
                if (role == "publisher") blockedReason = null
                log.infof("RabbitMQ unblocked the %s connection", role)
            }
        })
    }

    /** Reads the queue depths every [MONITOR_EVERY], and warns when messages get parked or nobody consumes. */
    private fun monitor() {
        try {
            while (running) {
                Thread.sleep(MONITOR_EVERY)
                val connection = consumer?.takeIf { it.isOpen } ?: continue
                try {
                    connection.createChannel().use { channel ->
                        val parked = channel.messageCount(Topology.STOCK_CHECK_DEAD_LETTERS)
                        val queued = channel.messageCount(Topology.STOCK_CHECK_QUEUE)
                        val consumers = channel.consumerCount(Topology.STOCK_CHECK_QUEUE)
                        val before = deadLetters
                        if (parked > (before ?: 0)) {
                            log.warnf(
                                "%d %s message(s) parked in %s (%d in total). The sweeper retries their prescriptions from the " +
                                    "database; the messages stay there for inspection in the RabbitMQ UI",
                                parked - (before ?: 0), if (before == null) "stock check" else "new stock check", Topology.STOCK_CHECK_DEAD_LETTERS, parked,
                            )
                        }
                        if (queued > 0 && consumers == 0L) {
                            log.warnf("%d stock check message(s) waiting in %s, and no instance is consuming them", queued, Topology.STOCK_CHECK_QUEUE)
                        }
                        log.debugf("Broker: %d stock check(s) queued (%d consumer(s)), %d parked", queued, consumers, parked)
                        deadLetters = parked
                        stockChecksQueued = queued
                    }
                } catch (e: Exception) {
                    log.debugf("Could not read the queue depths: %s", e.describe())
                }
            }
        } catch (_: InterruptedException) {
            // Shutting down.
        }
    }

    private fun Throwable.isPreconditionFailed(): Boolean = generateSequence(this) { it.cause }.take(10)
        .filterIsInstance<ShutdownSignalException>()
        .any { (it.reason as? AMQP.Channel.Close)?.replyCode == AMQP.PRECONDITION_FAILED }

    private fun String.withoutPassword(): String = runCatching {
        val parsed = URI(this)
        val user = parsed.userInfo?.substringBefore(':')
        URI(parsed.scheme, user?.let { "$it:***" }, parsed.host, parsed.port, parsed.path, null, null).toString()
    }.getOrDefault("(unparseable URI)")

    private companion object {
        val HEARTBEAT: Duration = Duration.ofSeconds(10)
        val RECONNECT_EVERY: Duration = Duration.ofSeconds(2)
        val MAX_RETRY_WAIT: Duration = Duration.ofSeconds(30)
        val MONITOR_EVERY: Duration = Duration.ofSeconds(15)
        val SHUTDOWN_GRACE: Duration = Duration.ofSeconds(5)
    }
}
