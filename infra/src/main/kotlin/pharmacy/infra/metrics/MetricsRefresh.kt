package pharmacy.infra.metrics

import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.event.Observes
import jakarta.inject.Singleton
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import pharmacy.infra.persistence.pretty
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Reads the database for the metrics that need it, every [every], so a Prometheus scrape never waits for the database
 * (nor adds to its load, however many scrape). Tests turn the schedule off and call [refresh] by hand.
 */
@Singleton
class MetricsRefresh(
    @ConfigProperty(name = "pharmacy.metrics.refresh.enabled") private val enabled: Boolean,
    @ConfigProperty(name = "pharmacy.metrics.refresh.every") private val every: Duration,
    private val business: BusinessMetrics,
    private val system: SystemMetrics,
) {
    private val log = Logger.getLogger(javaClass)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "metrics-refresh").apply { isDaemon = true } }

    fun start(@Observes event: StartupEvent) {
        if (!enabled) {
            log.info("Metrics refresh disabled (pharmacy.metrics.refresh.enabled=false)")
            return
        }
        log.infof("Metrics at /q/metrics; the ones that come from the database are read every %s", every.pretty())
        scheduler.scheduleWithFixedDelay(::refresh, 0, every.toMillis(), TimeUnit.MILLISECONDS)
    }

    fun stop(@Observes event: ShutdownEvent) {
        scheduler.shutdownNow()
    }

    /** Updates every metric that is read from the database. */
    fun refresh() {
        // An exception escaping a scheduled task would cancel every later run; each part fails on its own.
        listOf("business" to business::refresh, "system" to system::refresh).forEach { (name, refresh) ->
            try {
                refresh()
            } catch (e: Throwable) {
                log.warnf(e, "Refreshing the %s metrics failed; they have no value until the next try in %s", name, every.pretty())
            }
        }
    }
}
