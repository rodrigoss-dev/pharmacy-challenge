package pharmacy.infra.api

import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.Uni
import jakarta.annotation.PreDestroy
import jakarta.inject.Singleton
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import pharmacy.domain.NotFoundException
import pharmacy.infra.messaging.PrescriptionChangeFeed
import pharmacy.infra.persistence.DatabaseUnavailableException
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Supplier

/** One item of a live stream: the query's latest result, or word that it could not run. */
sealed interface LiveUpdate<out T : Any> {
    data class Value<T : Any>(val value: T) : LiveUpdate<T>

    /** The query failed (database unreachable...): screens say updates are delayed, and keep the last value. */
    data object Unavailable : LiveUpdate<Nothing>
}

/**
 * Turns a query into a live stream for the waiting room screens.
 *
 * The query is re-run whenever any prescription changes (a change to someone else's prescription can move you up the
 * queue) and, as a safety net, every [refreshEvery]: if the broker is down or an event is lost, screens still catch up
 * from the database. Only values that differ from the previous one are emitted.
 *
 * A failing query does not end the stream: it emits [LiveUpdate.Unavailable] once, and the next refresh that works
 * carries on. A database blip therefore no longer drops every phone at the same moment (and has them all reconnect at
 * once). Only [NotFoundException] ends a stream. Queries run on a small pool of their own, so live screens can never
 * take more than [maxConcurrentQueries] database connections away from check-ins and staff actions.
 */
@Singleton
class LiveUpdates(
    private val feed: PrescriptionChangeFeed,
    @ConfigProperty(name = "pharmacy.live-updates.refresh-every") private val refreshEvery: Duration,
    @ConfigProperty(name = "pharmacy.live-updates.max-concurrent-queries") private val maxConcurrentQueries: Int,
) {
    private val log = Logger.getLogger(javaClass)
    private val threads = AtomicInteger()
    private val queries: ExecutorService = Executors.newFixedThreadPool(maxConcurrentQueries) {
        Thread(it, "live-updates-${threads.incrementAndGet()}").apply { isDaemon = true }
    }
    private val open = AtomicInteger()
    private val failuresSinceWarning = AtomicInteger()
    private val lastWarning = AtomicLong(System.nanoTime() - WARN_EVERY.toNanos())

    /** Live streams open on this instance right now. */
    val openStreams: Int get() = open.get()

    /** [name] says what the stream shows, for the logs: `board`, `tracking <id>`. */
    fun <T : Any> watch(name: String, query: () -> T): Multi<LiveUpdate<T>> =
        Multi.createBy().merging().streams(
            Multi.createFrom().item(Unit),
            feed.changes.map { },
            Multi.createFrom().ticks().every(refreshEvery).map { },
        )
            // While a query runs, further triggers collapse into one: a burst of changes needs a single re-run.
            .onOverflow().dropPreviousItems()
            // Queries block on the database, so they run on their own small pool, one at a time per stream.
            .onItem().transformToUniAndConcatenate {
                Uni.createFrom().item(Supplier { attempt(name, query) }).runSubscriptionOn(queries)
            }
            .skip().repetitions()
            .onSubscription().invoke { _ -> log.debugf("Live stream opened: %s (%d open)", name, open.incrementAndGet()) }
            .onTermination().invoke { _, _ -> log.debugf("Live stream closed: %s (%d open)", name, open.decrementAndGet()) }

    @PreDestroy
    fun close() {
        queries.shutdownNow()
    }

    private fun <T : Any> attempt(name: String, query: () -> T): LiveUpdate<T> = try {
        LiveUpdate.Value(query())
    } catch (e: NotFoundException) {
        throw e
    } catch (e: Exception) {
        failed(name, e)
        LiveUpdate.Unavailable
    }

    /** At most one warning every [WARN_EVERY], counting the failures in between: a hundred screens fail together. */
    private fun failed(stream: String, e: Exception) {
        val failures = failuresSinceWarning.incrementAndGet()
        val now = System.nanoTime()
        val last = lastWarning.get()
        if (now - last < WARN_EVERY.toNanos() || !lastWarning.compareAndSet(last, now)) {
            log.debugf("Live update for %s failed: %s", stream, e.message)
            return
        }
        failuresSinceWarning.set(0)
        if (e is DatabaseUnavailableException) {
            log.warnf(
                "Live updates: %d refresh(es) could not reach the database recently (latest: %s). Screens say updates are " +
                    "delayed, and catch up by themselves once it is back",
                failures, stream,
            )
        } else {
            log.errorf(e, "Live updates: refresh of %s failed (%d failure(s) recently); screens say updates are delayed", stream, failures)
        }
    }

    private companion object {
        val WARN_EVERY: Duration = Duration.ofSeconds(30)
    }
}
