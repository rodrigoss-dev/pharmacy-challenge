package pharmacy.infra.messaging

import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.event.Observes
import jakarta.inject.Singleton
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import pharmacy.domain.ConcurrentUpdateException
import pharmacy.domain.PrescriptionId
import pharmacy.domain.usecase.ReserveStock
import pharmacy.domain.usecase.StalledStockChecks
import pharmacy.infra.persistence.DatabaseHealth
import pharmacy.infra.persistence.DatabaseUnavailableException
import pharmacy.infra.persistence.Transactions
import pharmacy.infra.persistence.pretty
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The automatic version of the staff console's *Retry stock check* button. Every [every], it runs the stock check for
 * prescriptions that have waited more than [after] for theirs: the message is late, lost or parked in the dead-letter
 * queue, or RabbitMQ is down. The message stays the fast path; this is the guarantee, read from the database, the
 * source of truth. Patients see "taking longer than usual" only after 30s, so most never notice a broker outage.
 *
 * Safe on several instances at once, and next to the consumer: the stock check is idempotent and optimistically
 * locked. A prescription whose stock check keeps failing is retried with growing pauses, up to [MAX_BACKOFF].
 */
@Singleton
class StockCheckSweeper(
    @ConfigProperty(name = "pharmacy.stock-check.sweeper.enabled") private val enabled: Boolean,
    @ConfigProperty(name = "pharmacy.stock-check.sweeper.every") private val every: Duration,
    @ConfigProperty(name = "pharmacy.stock-check.sweeper.after") private val after: Duration,
    private val stalledStockChecks: StalledStockChecks,
    private val reserveStock: ReserveStock,
    private val transactions: Transactions,
    private val database: DatabaseHealth,
) {
    private val log = Logger.getLogger(javaClass)
    private val failures = ConcurrentHashMap<PrescriptionId, Failure>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "stock-check-sweeper").apply { isDaemon = true } }

    private data class Failure(val count: Int, val retryAt: Instant)

    /** When the last sweep ran; null if the sweeper is disabled. */
    @Volatile
    var lastSweepAt: Instant? = null
        private set

    val interval: Duration get() = every

    fun start(@Observes event: StartupEvent) {
        if (!enabled) {
            log.info("Stock check sweeper disabled (pharmacy.stock-check.sweeper.enabled=false)")
            return
        }
        lastSweepAt = Instant.now()
        log.infof("Stock check sweeper: every %s, runs the stock checks that have waited more than %s", every.pretty(), after.pretty())
        scheduler.scheduleWithFixedDelay(::sweepSafely, every.toMillis(), every.toMillis(), TimeUnit.MILLISECONDS)
    }

    fun stop(@Observes event: ShutdownEvent) {
        scheduler.shutdownNow()
    }

    /** Runs one sweep now. Returns how many prescriptions it moved on. */
    fun sweep(): Int {
        lastSweepAt = Instant.now()
        if (!database.checkIfDue()) return 0 // DatabaseHealth logs the outage

        val stalled = try {
            transactions.read("finding stalled stock checks") { stalledStockChecks(after) }
        } catch (_: DatabaseUnavailableException) {
            return 0
        }
        val now = Instant.now()
        val due = stalled.filter { (failures[it.id]?.retryAt ?: Instant.MIN) <= now }
        failures.keys.retainAll(stalled.map { it.id }.toSet()) // forget prescriptions that moved on some other way
        if (due.isEmpty()) return 0

        log.infof(
            "Sweeper: %d stock check(s) waiting more than %s (%s); running them now",
            due.size, after.pretty(), due.joinToString { "#${it.ticketNumber}" },
        )
        var moved = 0
        for (prescription in due) {
            val ticket = "#${prescription.ticketNumber}"
            try {
                val result = transactions.transaction("stock check for $ticket (sweeper)") { reserveStock(prescription.id) }
                failures.remove(prescription.id)
                log.infof("Sweeper: stock check for %s done: %s", ticket, result.status)
                moved++
            } catch (_: DatabaseUnavailableException) {
                log.warnf("Sweeper: stopped at %s, the database is unavailable; the next sweep picks up the rest", ticket)
                break
            } catch (_: ConcurrentUpdateException) {
                log.debugf("Sweeper: %s was moved by someone else at the same moment", ticket)
            } catch (e: Exception) {
                val failure = failures.merge(prescription.id, Failure(1, now + every)) { previous, _ ->
                    Failure(previous.count + 1, now + minOf(every.multipliedBy(1L shl minOf(previous.count, 10)), MAX_BACKOFF))
                }!!
                log.errorf(
                    e, "Sweeper: stock check for %s failed (%d time(s) in a row); next try in %s",
                    ticket, failure.count, Duration.between(now, failure.retryAt).pretty(),
                )
            }
        }
        return moved
    }

    private fun sweepSafely() {
        // An exception escaping a scheduled task would cancel every later run.
        try {
            sweep()
        } catch (e: Throwable) {
            log.errorf(e, "Sweeper: unexpected failure; trying again in %s", every.pretty())
        }
    }

    private companion object {
        val MAX_BACKOFF: Duration = Duration.ofMinutes(5)
    }
}
