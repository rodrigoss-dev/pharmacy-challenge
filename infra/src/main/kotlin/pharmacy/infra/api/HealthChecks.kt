package pharmacy.infra.api

import io.smallrye.health.api.Wellness
import jakarta.inject.Singleton
import org.eclipse.microprofile.health.HealthCheck
import org.eclipse.microprofile.health.HealthCheckResponse
import org.eclipse.microprofile.health.Liveness
import org.jboss.logging.Logger
import pharmacy.infra.government.GovernmentCommitRelay
import pharmacy.infra.messaging.RabbitMqMessaging
import pharmacy.infra.messaging.StockCheckSweeper
import pharmacy.infra.persistence.pretty
import java.time.Clock
import java.time.Duration

// Health checks for orchestrators and load balancers, under /q/health (SmallRye Health):
// - /q/health/live:  the background loops (outbox relay, stock check sweeper, government commit relay) still run. If one
//                    is stuck, restarting the instance is the fix.
// - /q/health/ready: the database answers (the datasource check Quarkus provides). Without it the instance can only
//                    answer 503, so traffic should go elsewhere.
// - /q/health/well:  RabbitMQ. Informational on purpose: the app keeps taking check-ins without it, so a broker outage
//                    must neither restart instances nor take them out of the load balancer.
// The staff console reads /health instead (HealthResource), which answers even when the database does not.

@Liveness
@Singleton
class BackgroundWorkCheck(
    private val messaging: RabbitMqMessaging,
    private val sweeper: StockCheckSweeper,
    private val governmentRelay: GovernmentCommitRelay,
    private val clock: Clock,
) : HealthCheck {
    private val log = Logger.getLogger(javaClass)

    override fun call(): HealthCheckResponse {
        val now = clock.instant()
        val relay = messaging.relayLastLoopAt?.let { Duration.between(it, now) }
        val sweep = sweeper.lastSweepAt?.let { Duration.between(it, now) }
        val government = governmentRelay.lastRunAt?.let { Duration.between(it, now) }
        val relayStuck = relay != null && relay > RELAY_STUCK_AFTER
        val sweeperStuck = sweep != null && sweep > maxOf(sweeper.interval.multipliedBy(3), SWEEPER_STUCK_AFTER)
        val governmentStuck = government != null && government > maxOf(governmentRelay.interval.multipliedBy(3), GOVERNMENT_RELAY_STUCK_AFTER)
        if (relayStuck) log.errorf("Liveness check failing: the outbox relay has not run for %s", relay?.pretty())
        if (sweeperStuck) log.errorf("Liveness check failing: the stock check sweeper has not run for %s", sweep?.pretty())
        if (governmentStuck) log.errorf("Liveness check failing: the government commit relay has not run for %s", government?.pretty())
        return HealthCheckResponse.named("background work")
            .status(!relayStuck && !sweeperStuck && !governmentStuck)
            .withData("outboxRelay", relay?.let { "ran ${it.pretty()} ago" } ?: "disabled")
            .withData("stockCheckSweeper", sweep?.let { "ran ${it.pretty()} ago" } ?: "disabled")
            .withData("governmentCommitRelay", government?.let { "ran ${it.pretty()} ago" } ?: "disabled")
            .build()
    }

    private companion object {
        val RELAY_STUCK_AFTER: Duration = Duration.ofMinutes(2)
        val SWEEPER_STUCK_AFTER: Duration = Duration.ofMinutes(2)
        val GOVERNMENT_RELAY_STUCK_AFTER: Duration = Duration.ofMinutes(2)
    }
}

@Wellness
@Singleton
class BrokerCheck(private val messaging: RabbitMqMessaging) : HealthCheck {
    override fun call(): HealthCheckResponse {
        val broker = messaging.status
        return HealthCheckResponse.named("rabbitmq")
            .status(broker.enabled && broker.isUp && broker.blocked == null)
            .withData("enabled", broker.enabled)
            .withData("publisherConnected", broker.publisherConnected)
            .withData("consumerConnected", broker.consumerConnected)
            .withData("stockCheckConsumerActive", broker.stockCheckConsumerActive)
            .withData("liveUpdatesFeedActive", broker.liveUpdatesFeedActive)
            .withData("blocked", broker.blocked ?: "no")
            .withData("deadLetters", broker.deadLetters?.toString() ?: "unknown")
            .withData("stockChecksQueued", broker.stockChecksQueued?.toString() ?: "unknown")
            .build()
    }
}
