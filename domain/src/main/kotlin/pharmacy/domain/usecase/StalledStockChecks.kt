package pharmacy.domain.usecase

import pharmacy.domain.Prescription
import pharmacy.domain.PrescriptionStatus
import pharmacy.domain.port.PrescriptionRepository
import java.time.Clock
import java.time.Duration

/**
 * Prescriptions whose automatic stock check should have happened by now: its message is late, lost or parked (broker
 * down, database briefly unreachable, a consumer that kept failing). Oldest ticket first. Whoever runs the stock checks
 * retries these; the database, not the broker, says what still needs doing.
 */
class StalledStockChecks(
    private val prescriptions: PrescriptionRepository,
    private val clock: Clock,
) {
    operator fun invoke(waitingLongerThan: Duration): List<Prescription> {
        val now = clock.now()
        return prescriptions.findByStatus(listOf(PrescriptionStatus.RECEIVED))
            .filter { Duration.between(it.updatedAt, now) >= waitingLongerThan }
    }

    /** How many there are, without loading them: for the health report and the metrics. */
    fun count(waitingLongerThan: Duration): Int =
        prescriptions.countInStatusSince(PrescriptionStatus.RECEIVED, clock.now().minus(waitingLongerThan))
}
