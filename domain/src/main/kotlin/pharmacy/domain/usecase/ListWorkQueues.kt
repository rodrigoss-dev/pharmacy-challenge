package pharmacy.domain.usecase

import pharmacy.domain.Prescription
import pharmacy.domain.PrescriptionStatus
import pharmacy.domain.PrescriptionStatus.AWAITING_APPROVAL
import pharmacy.domain.PrescriptionStatus.AWAITING_PACKAGING
import pharmacy.domain.PrescriptionStatus.CALLED
import pharmacy.domain.PrescriptionStatus.READY_FOR_PICKUP
import pharmacy.domain.PrescriptionStatus.RECEIVED
import pharmacy.domain.port.PrescriptionRepository

/** Each list is in work order: oldest ticket first. */
data class WorkQueues(
    /** Waiting for the automatic stock check. Normally empty within a second or two. */
    val stockCheck: List<Prescription>,
    val pharmacist: List<Prescription>,
    val packaging: List<Prescription>,
    /** Packed, or already called out and waiting for the patient to come to the counter. */
    val pickup: List<Prescription>,
)

/** The staff console. */
class ListWorkQueues(private val prescriptions: PrescriptionRepository) {
    operator fun invoke(): WorkQueues {
        val active = prescriptions.findByStatus(PrescriptionStatus.entries.filterNot { it.isFinal })
        return WorkQueues(
            stockCheck = active.filter { it.status == RECEIVED },
            pharmacist = active.filter { it.status == AWAITING_APPROVAL },
            packaging = active.filter { it.status == AWAITING_PACKAGING },
            pickup = active.filter { it.status == READY_FOR_PICKUP || it.status == CALLED },
        )
    }
}
