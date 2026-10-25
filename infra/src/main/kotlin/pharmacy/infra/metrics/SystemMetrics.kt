package pharmacy.infra.metrics

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.inject.Singleton
import pharmacy.infra.api.LiveUpdates
import pharmacy.infra.messaging.RabbitMqMessaging
import pharmacy.infra.persistence.Backlogs
import pharmacy.infra.persistence.DatabaseHealth
import pharmacy.infra.persistence.DatabaseUnavailableException

/**
 * The same signals as `/health` (which the staff console reads), as Prometheus metrics, next to the JVM, HTTP and
 * connection pool metrics Quarkus provides. The database, the broker and the live streams are read from this
 * instance's memory on every scrape; the outbox backlogs (events for the broker, commits for the government system) and
 * the stalled stock checks come from the database, read by [refresh], and have no value while it cannot be read.
 */
@Singleton
class SystemMetrics(
    registry: MeterRegistry,
    database: DatabaseHealth,
    messaging: RabbitMqMessaging,
    liveUpdates: LiveUpdates,
    private val backlogs: Backlogs,
) {
    private data class Snapshot(
        val pendingEvents: Int,
        val oldestPendingEventSeconds: Double,
        val stalledStockChecks: Int,
        val pendingGovernmentCommits: Int,
        val oldestPendingGovernmentCommitSeconds: Double,
        val rejectedGovernmentCommits: Int,
    )

    @Volatile
    private var snapshot: Snapshot? = null

    init {
        fun gauge(name: String, description: String, unit: String? = null, value: () -> Double) {
            val builder = Gauge.builder(name) { value() }.description(description)
            if (unit != null) builder.baseUnit(unit)
            builder.register(registry)
        }

        gauge("pharmacy.database.up", "1 while the database answers, 0 while the circuit is open") { if (database.isAvailable) 1.0 else 0.0 }
        gauge("pharmacy.broker.up", "1 while both RabbitMQ connections (publishing and consuming) are open") { if (messaging.status.isUp) 1.0 else 0.0 }
        gauge("pharmacy.broker.blocked", "1 while RabbitMQ blocks publishing (memory or disk alarm)") { if (messaging.status.blocked != null) 1.0 else 0.0 }
        gauge("pharmacy.broker.dead.letters", "Stock check messages parked in the dead-letter queue") { messaging.status.deadLetters?.toDouble() ?: Double.NaN }
        gauge("pharmacy.broker.stock.checks.queued", "Stock check messages waiting in the queue") { messaging.status.stockChecksQueued?.toDouble() ?: Double.NaN }
        gauge("pharmacy.live.streams.open", "Live screens (SSE) connected to this instance") { liveUpdates.openStreams.toDouble() }
        gauge("pharmacy.outbox.pending.events", "Events committed but not yet confirmed by the broker") { snapshot?.pendingEvents?.toDouble() ?: Double.NaN }
        gauge("pharmacy.outbox.oldest.age", "Age of the oldest event waiting in the outbox", "seconds") { snapshot?.oldestPendingEventSeconds ?: Double.NaN }
        gauge("pharmacy.stock.checks.stalled", "Prescriptions whose stock check is later than expected") { snapshot?.stalledStockChecks?.toDouble() ?: Double.NaN }
        gauge("pharmacy.government.commits.pending", "Commits to the government system written but not accepted by it yet") { snapshot?.pendingGovernmentCommits?.toDouble() ?: Double.NaN }
        gauge("pharmacy.government.commits.oldest.pending.age", "Age of the oldest commit waiting for the government system", "seconds") { snapshot?.oldestPendingGovernmentCommitSeconds ?: Double.NaN }
        gauge("pharmacy.government.commits.rejected", "Commits the government refused for good: only a person can sort them out") { snapshot?.rejectedGovernmentCommits?.toDouble() ?: Double.NaN }
    }

    /** Reads the database and updates the gauges. An unexpected failure clears them and is thrown on. */
    fun refresh() {
        var loaded: Snapshot? = null
        try {
            loaded = load()
        } catch (_: DatabaseUnavailableException) {
            // DatabaseHealth reports the outage; the gauges have no value meanwhile.
        } finally {
            snapshot = loaded
        }
    }

    private fun load(): Snapshot {
        val waiting = backlogs.read()
        return Snapshot(
            pendingEvents = waiting.events.events,
            oldestPendingEventSeconds = waiting.events.oldestAge()?.toMillis()?.div(1000.0) ?: 0.0,
            stalledStockChecks = waiting.stalledStockChecks,
            pendingGovernmentCommits = waiting.governmentCommits.pending,
            oldestPendingGovernmentCommitSeconds = waiting.governmentCommits.oldestPendingAge()?.toMillis()?.div(1000.0) ?: 0.0,
            rejectedGovernmentCommits = waiting.governmentCommits.rejected,
        )
    }
}
