package pharmacy.infra.api

import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import pharmacy.infra.messaging.RabbitMqMessaging
import pharmacy.infra.persistence.Backlogs
import pharmacy.infra.persistence.DatabaseUnavailableException

/**
 * Read by the staff console to warn when the database or the broker is down, or work is piling up. Always answers,
 * even when the database does not: then the database fields are null and `database` says "down".
 */
@Path("/health")
class HealthResource(
    private val messaging: RabbitMqMessaging,
    private val backlogs: Backlogs,
    private val liveUpdates: LiveUpdates,
) {
    @GET
    fun health(): HealthReport {
        val waiting = try {
            backlogs.read()
        } catch (_: DatabaseUnavailableException) {
            null
        }
        val broker = messaging.status
        return HealthReport(
            database = if (waiting != null) "up" else "down",
            broker = if (broker.isUp) "up" else "down",
            brokerBlocked = broker.blocked,
            pendingEvents = waiting?.events?.events,
            oldestPendingEventSeconds = waiting?.events?.oldestAge()?.toSeconds(),
            stalledStockChecks = waiting?.stalledStockChecks,
            deadLetters = broker.deadLetters,
            pendingGovernmentCommits = waiting?.governmentCommits?.pending,
            oldestPendingGovernmentCommitSeconds = waiting?.governmentCommits?.oldestPendingAge()?.toSeconds(),
            rejectedGovernmentCommits = waiting?.governmentCommits?.rejected,
            liveStreams = liveUpdates.openStreams,
        )
    }
}
