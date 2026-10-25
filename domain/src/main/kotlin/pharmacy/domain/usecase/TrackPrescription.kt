package pharmacy.domain.usecase

import pharmacy.domain.Prescription
import pharmacy.domain.PrescriptionId
import pharmacy.domain.PrescriptionStatus
import pharmacy.domain.port.PrescriptionRepository
import java.time.Clock
import java.time.Duration

data class PrescriptionTracking(
    val prescription: Prescription,
    /** Patients in the same queue who checked in earlier; counted for the pharmacist and packaging queues, 0 elsewhere. */
    val peopleAhead: Int,
    /** The automatic stock check is taking longer than it should; staff can see and retry it. */
    val delayed: Boolean,
)

/** What a patient sees on their phone while they wait. */
class TrackPrescription(
    private val prescriptions: PrescriptionRepository,
    private val clock: Clock,
) {
    operator fun invoke(id: PrescriptionId): PrescriptionTracking {
        val prescription = prescriptions.findById(id) ?: throw prescriptionNotFound(id)
        return PrescriptionTracking(
            prescription = prescription,
            peopleAhead = if (prescription.status in QUEUES) prescriptions.countAhead(prescription) else 0,
            delayed = prescription.status == PrescriptionStatus.RECEIVED &&
                Duration.between(prescription.updatedAt, clock.now()) >= STOCK_CHECK_EXPECTED_WITHIN,
        )
    }

    companion object {
        val STOCK_CHECK_EXPECTED_WITHIN: Duration = Duration.ofSeconds(30)

        /** The queues people wait in for staff: the stock check takes a second, and the pickup is called by number. */
        private val QUEUES = setOf(
            PrescriptionStatus.AWAITING_APPROVAL,
            PrescriptionStatus.AWAITING_PACKAGING,
        )
    }
}
