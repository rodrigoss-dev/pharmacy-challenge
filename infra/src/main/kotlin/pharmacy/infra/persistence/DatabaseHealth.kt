package pharmacy.infra.persistence

import jakarta.annotation.PreDestroy
import jakarta.inject.Singleton
import org.jboss.logging.Logger
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource

/**
 * Whether the database is reachable, as seen by every database call this instance makes ([Transactions] reports each
 * outcome). A circuit breaker: once a call finds the database unreachable, work fails fast instead of every caller
 * waiting for its own connection timeout, and background work (relay, consumer, sweeper) pauses. While it is down, a
 * background probe asks the database at most every [PROBE_EVERY]; the first answer closes the circuit again.
 *
 * Logs one warning when the database goes away, a reminder every [REMIND_EVERY] while it stays away, and how long the
 * outage lasted when it is back.
 */
@Singleton
class DatabaseHealth(private val dataSource: DataSource) {
    private val log = Logger.getLogger(javaClass)
    private val lastProbe = AtomicLong(System.nanoTime() - PROBE_EVERY.toNanos())
    private val probing = AtomicBoolean(false)
    private val prober = Executors.newSingleThreadExecutor { Thread(it, "database-probe").apply { isDaemon = true } }

    @Volatile
    private var downSince: Instant? = null

    @Volatile
    private var lastFailure: String? = null

    @Volatile
    private var lastReminder: Instant = Instant.EPOCH

    val isAvailable: Boolean get() = downSince == null

    /** The last failure seen while unreachable, for the health report. */
    val lastFailureSummary: String? get() = if (isAvailable) null else lastFailure

    /** Called with the failure of any database call. Only failures meaning "unreachable" open the circuit. */
    fun failed(e: Throwable, during: String) {
        if (DatabaseFailure.of(e) != DatabaseFailure.UNAVAILABLE) return
        val summary = e.databaseSummary()
        synchronized(this) {
            lastFailure = summary
            if (downSince == null) {
                downSince = Instant.now()
                lastReminder = downSince!!
                log.warnf(
                    "Database unreachable (while %s): %s. Requests get a 503, and background work pauses until it answers again",
                    during, summary,
                )
            } else {
                remindIfDue()
            }
        }
    }

    fun succeeded() {
        if (downSince == null) return
        synchronized(this) {
            val since = downSince ?: return
            downSince = null
            log.infof("Database reachable again after %s of outage. Resuming normal work", Duration.between(since, Instant.now()).pretty())
        }
    }

    /**
     * True if the database is reachable. While it is not, starts a probe in the background if none ran in the last
     * [PROBE_EVERY], and answers right away: no caller waits for a probe, which can take as long as the pool's
     * acquisition timeout when the database hangs. [awaitAvailable] polls this.
     */
    fun checkIfDue(): Boolean {
        if (isAvailable) return true
        val now = System.nanoTime()
        val last = lastProbe.get()
        if (now - last >= PROBE_EVERY.toNanos() && probing.compareAndSet(false, true)) {
            lastProbe.set(now)
            try {
                prober.execute {
                    try {
                        probe()
                    } finally {
                        lastProbe.set(System.nanoTime())
                        probing.set(false)
                    }
                }
            } catch (_: RejectedExecutionException) {
                probing.set(false) // shutting down
            }
        }
        return isAvailable
    }

    /** Asks the database whether it is there, and records the answer. */
    fun probe(): Boolean = try {
        val valid = dataSource.connection.use { it.isValid(PROBE_TIMEOUT_SECONDS) }
        if (valid) {
            succeeded()
        } else {
            synchronized(this) { remindIfDue() }
        }
        valid
    } catch (e: Exception) {
        failed(e, "checking whether it is back")
        false
    }

    /**
     * Blocks until the database is reachable, [timeout] passes or [keepWaiting] turns false; returns whether it is
     * reachable. Used by background work that has nothing better to do while the database is away.
     */
    fun awaitAvailable(timeout: Duration, keepWaiting: () -> Boolean = { true }): Boolean {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (!checkIfDue()) {
            if (!keepWaiting() || System.nanoTime() > deadline) return isAvailable
            Thread.sleep(WAIT_STEP)
        }
        return true
    }

    @PreDestroy
    fun close() {
        prober.shutdownNow()
    }

    private fun remindIfDue() {
        val since = downSince ?: return
        val now = Instant.now()
        if (Duration.between(lastReminder, now) < REMIND_EVERY) return
        lastReminder = now
        log.warnf("Database still unreachable after %s: %s", Duration.between(since, now).pretty(), lastFailure)
    }

    private companion object {
        val PROBE_EVERY: Duration = Duration.ofSeconds(2)
        val REMIND_EVERY: Duration = Duration.ofSeconds(30)
        val WAIT_STEP: Duration = Duration.ofMillis(250)
        const val PROBE_TIMEOUT_SECONDS = 2
    }
}

/** `45s`, `3m 12s`, `1h 4m`. */
fun Duration.pretty(): String = when {
    toMillis() < 1_000 -> "${toMillis()} ms"
    toMinutes() < 1 -> "${toSeconds()}s"
    toHours() < 1 -> "${toMinutes()}m ${toSecondsPart()}s"
    else -> "${toHours()}h ${toMinutesPart()}m"
}
