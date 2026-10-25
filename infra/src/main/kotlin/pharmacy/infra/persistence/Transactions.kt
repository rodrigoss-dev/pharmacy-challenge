package pharmacy.infra.persistence

import io.quarkus.narayana.jta.QuarkusTransaction
import jakarta.inject.Singleton
import org.jboss.logging.Logger
import pharmacy.domain.DomainException
import java.time.Duration
import java.util.concurrent.ThreadLocalRandom

/** The database could not do [action] right now (unreachable, overloaded or too slow). Worth trying again later: HTTP 503. */
class DatabaseUnavailableException(val action: String, val failure: DatabaseFailure, cause: Throwable) :
    RuntimeException("Database unavailable for $action: ${cause.databaseSummary()}", cause)

/**
 * The one way the doors into the domain (REST endpoints, the stock check consumer, background jobs) run database work.
 *
 * - [transaction] commits everything the block wrote, or nothing; [read] runs without one.
 * - A lost race (deadlock, serialization failure, lock timeout) is retried right away, a few times: nothing was saved.
 * - When the database is unreachable or too slow, the call fails with [DatabaseUnavailableException] rather than an
 *   unexpected error, and [DatabaseHealth] is told. While it is known to be down, calls fail fast (see [DatabaseHealth]).
 * - Slow work is logged as a warning, every unit of work at TRACE.
 */
@Singleton
class Transactions(private val health: DatabaseHealth) {
    private val log = Logger.getLogger(javaClass)

    /**
     * Runs [block] in a new transaction. [retryIf] adds failures worth one more attempt (e.g. a check-in that raced its
     * own retry); lost races are always retried.
     */
    fun <T> transaction(
        action: String,
        retryIf: (Throwable) -> Boolean = { false },
        slowAfter: Duration = SLOW,
        block: () -> T,
    ): T = run(action, retryIf, slowAfter) { QuarkusTransaction.requiringNew().timeout(TIMEOUT_SECONDS).call(block) }

    /** Runs [block] outside a transaction: plain reads, each statement on its own. */
    fun <T> read(action: String, block: () -> T): T = run(action, { false }, SLOW, block)

    private fun <T> run(action: String, retryIf: (Throwable) -> Boolean, slowAfter: Duration, work: () -> T): T {
        if (!health.checkIfDue()) {
            throw DatabaseUnavailableException(action, DatabaseFailure.UNAVAILABLE, IllegalStateException(health.lastFailureSummary ?: "database unreachable"))
        }
        var attempt = 1
        while (true) {
            val started = System.nanoTime()
            try {
                val result = work()
                health.succeeded()
                val took = Duration.ofNanos(System.nanoTime() - started)
                if (took > slowAfter) {
                    log.warnf("Slow database work: %s took %d ms%s", action, took.toMillis(), if (attempt > 1) " (attempt $attempt)" else "")
                } else {
                    log.tracef("%s: done in %d ms", action, took.toMillis()) // TRACE: the relay alone polls ten times a second
                }
                return result
            } catch (e: DomainException) {
                health.succeeded() // the database answered; the business rules said no
                throw e
            } catch (e: Exception) {
                val failure = DatabaseFailure.of(e)
                val retryable = failure == DatabaseFailure.CONTENTION || retryIf(e)
                if (retryable && attempt < MAX_ATTEMPTS) {
                    if (failure == DatabaseFailure.CONTENTION) {
                        log.warnf("%s: %s, retrying (attempt %d of %d)", action, e.databaseSummary(), attempt + 1, MAX_ATTEMPTS)
                    } else {
                        // The caller asked for this one (see retryIf): a race it expects and handles, not trouble.
                        log.infof("%s: raced with another request, running it again (attempt %d of %d)", action, attempt + 1, MAX_ATTEMPTS)
                        log.debugf("%s: the race was %s", action, e.databaseSummary())
                    }
                    Thread.sleep(backoff(attempt))
                    attempt++
                    continue
                }
                when (failure) {
                    null -> throw e // a bug or bad data: the caller decides, usually a 500 with the stack trace
                    DatabaseFailure.UNAVAILABLE -> health.failed(e, action)
                    DatabaseFailure.TIMEOUT ->
                        log.warnf("%s took longer than the statement timeout and was cancelled: %s", action, e.databaseSummary())
                    DatabaseFailure.CONTENTION ->
                        log.warnf("%s kept losing races with other transactions after %d attempts: %s", action, attempt, e.databaseSummary())
                }
                throw DatabaseUnavailableException(action, failure, e)
            }
        }
    }

    /** 25-50 ms, then 50-100 ms...: long enough for the other transaction to finish, short for the caller. */
    private fun backoff(attempt: Int): Duration {
        val base = 25L shl (attempt - 1)
        return Duration.ofMillis(base + ThreadLocalRandom.current().nextLong(base + 1))
    }

    private companion object {
        const val MAX_ATTEMPTS = 3

        /** Longer than any statement may run (statement_timeout), short enough to free locks if something hangs. */
        const val TIMEOUT_SECONDS = 30
        val SLOW: Duration = Duration.ofSeconds(1)
    }
}
