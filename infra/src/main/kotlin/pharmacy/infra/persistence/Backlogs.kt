package pharmacy.infra.persistence

import jakarta.inject.Singleton
import pharmacy.domain.usecase.StalledStockChecks
import pharmacy.domain.usecase.TrackPrescription

/**
 * What is waiting right now, read from the database: events for the broker, stock checks that are late, commits for
 * the government system. The one place for what `/health` (the staff console) and the system metrics both report.
 */
@Singleton
class Backlogs(
    private val outbox: JooqEventOutbox,
    private val governmentCommits: JooqGovernmentCommits,
    private val stalledStockChecks: StalledStockChecks,
    private val transactions: Transactions,
) {
    data class Snapshot(
        val events: JooqEventOutbox.Backlog,
        val stalledStockChecks: Int,
        val governmentCommits: JooqGovernmentCommits.Backlog,
    )

    /** Throws [DatabaseUnavailableException] when the database cannot be read. */
    fun read(): Snapshot = transactions.read("backlogs: events, stock checks, government commits") {
        Snapshot(
            events = outbox.backlogDetails(),
            stalledStockChecks = stalledStockChecks.count(TrackPrescription.STOCK_CHECK_EXPECTED_WITHIN),
            governmentCommits = governmentCommits.backlog(),
        )
    }
}
