package pharmacy.domain

import java.time.Instant
import java.util.UUID

/** Raised every time a prescription moves forward (including when it is first received). */
data class PrescriptionStatusChanged(
    val eventId: UUID,
    val prescriptionId: PrescriptionId,
    val ticketNumber: Int,
    val status: PrescriptionStatus,
    val occurredAt: Instant,
) {
    companion object {
        fun of(prescription: Prescription) = PrescriptionStatusChanged(
            eventId = UUID.randomUUID(),
            prescriptionId = prescription.id,
            ticketNumber = prescription.ticketNumber,
            status = prescription.status,
            occurredAt = prescription.updatedAt,
        )
    }
}
