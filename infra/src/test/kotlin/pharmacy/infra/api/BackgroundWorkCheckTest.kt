package pharmacy.infra.api

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.eclipse.microprofile.health.HealthCheckResponse
import pharmacy.domain.usecase.StalledStockChecks
import pharmacy.domain.usecase.ReserveStock
import pharmacy.infra.government.GovernmentApiClient
import pharmacy.infra.government.GovernmentCommitRelay
import pharmacy.infra.messaging.RabbitMqMessaging
import pharmacy.infra.messaging.StockCheckSweeper
import pharmacy.infra.persistence.DatabaseHealth
import pharmacy.infra.persistence.JooqGovernmentCommits
import pharmacy.infra.persistence.Transactions
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The liveness check: an instance whose background loops stopped is one to restart. */
@QuarkusTest
class BackgroundWorkCheckTest {
    @Inject lateinit var messaging: RabbitMqMessaging
    @Inject lateinit var stalled: StalledStockChecks
    @Inject lateinit var reserveStock: ReserveStock
    @Inject lateinit var transactions: Transactions
    @Inject lateinit var health: DatabaseHealth
    @Inject lateinit var commits: JooqGovernmentCommits
    @Inject lateinit var government: GovernmentApiClient

    private fun sweeper() = StockCheckSweeper(false, Duration.ofSeconds(10), Duration.ZERO, stalled, reserveStock, transactions, health)

    private fun relay() = GovernmentCommitRelay(false, Duration.ofSeconds(2), commits, government, transactions, health)

    private fun check(sweeper: StockCheckSweeper, relay: GovernmentCommitRelay, minutesLater: Long) = BackgroundWorkCheck(
        messaging, sweeper, relay, Clock.fixed(Instant.now().plus(Duration.ofMinutes(minutesLater)), ZoneOffset.UTC),
    ).call()

    @Test
    fun `loops that are disabled are not stuck`() {
        val response = check(sweeper(), relay(), minutesLater = 60)

        assertEquals(HealthCheckResponse.Status.UP, response.status)
        val data = response.data.get()
        assertEquals("disabled", data["outboxRelay"])
        assertEquals("disabled", data["stockCheckSweeper"])
        assertEquals("disabled", data["governmentCommitRelay"])
    }

    @Test
    fun `loops that went round a moment ago are fine`() {
        val sweeper = sweeper().also { it.sweep() }
        val relay = relay().also { it.relay() }

        val response = check(sweeper, relay, minutesLater = 0)

        assertEquals(HealthCheckResponse.Status.UP, response.status)
        assertTrue(response.data.get()["stockCheckSweeper"].toString().startsWith("ran "))
        assertTrue(response.data.get()["governmentCommitRelay"].toString().startsWith("ran "))
    }

    @Test
    fun `a sweeper that has not gone round for minutes is stuck, and the instance is not alive`() {
        val sweeper = sweeper().also { it.sweep() }

        assertEquals(HealthCheckResponse.Status.UP, check(sweeper, relay(), minutesLater = 1).status, "a minute is not long")
        assertEquals(HealthCheckResponse.Status.DOWN, check(sweeper, relay(), minutesLater = 3).status)
    }

    @Test
    fun `a government commit relay that has not gone round for minutes is stuck`() {
        val relay = relay().also { it.relay() }

        assertEquals(HealthCheckResponse.Status.UP, check(sweeper(), relay, minutesLater = 1).status)
        assertEquals(HealthCheckResponse.Status.DOWN, check(sweeper(), relay, minutesLater = 3).status)
    }
}
