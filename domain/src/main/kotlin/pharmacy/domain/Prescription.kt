package pharmacy.domain

import java.time.Instant
import java.util.UUID

@JvmInline
value class PrescriptionId(val value: UUID) {
    override fun toString() = value.toString()

    companion object {
        fun random() = PrescriptionId(UUID.randomUUID())
    }
}

data class PrescriptionItem(
    val medicationId: MedicationId,
    /** Copied at check-in so the prescription stays readable even if the catalog changes. */
    val medicationName: String,
    /** Number of packs. */
    val quantity: Int,
)

/**
 * The prescription-to-patient cycle:
 *
 * RECEIVED -> AWAITING_APPROVAL -> AWAITING_PACKAGING -> READY_FOR_PICKUP -> CALLED -> COLLECTED
 *     \-> OUT_OF_STOCK      \-> REJECTED                                          \-> NO_SHOW
 */
enum class PrescriptionStatus {
    /** Checked in; the inventory is being verified and reserved. */
    RECEIVED,

    /** Stock is reserved; waiting in the pharmacist queue. */
    AWAITING_APPROVAL,

    /** Approved by a pharmacist; waiting in the packaging queue. */
    AWAITING_PACKAGING,

    /** Packed; waiting for the pharmacist to call the patient's number. */
    READY_FOR_PICKUP,

    /** The patient's number has been called out. */
    CALLED,

    /** Handed over to the patient. */
    COLLECTED,

    /** Not enough stock for at least one medication. */
    OUT_OF_STOCK,

    /** A pharmacist declined to dispense the prescription. */
    REJECTED,

    /** The patient did not answer when their number was called: the prescription is given up and its packs go back on the shelf. */
    NO_SHOW;

    val isFinal: Boolean get() = this == COLLECTED || this == OUT_OF_STOCK || this == REJECTED || this == NO_SHOW

    fun canMoveTo(next: PrescriptionStatus): Boolean = when (this) {
        RECEIVED -> next == AWAITING_APPROVAL || next == OUT_OF_STOCK
        AWAITING_APPROVAL -> next == AWAITING_PACKAGING || next == REJECTED
        AWAITING_PACKAGING -> next == READY_FOR_PICKUP
        READY_FOR_PICKUP -> next == CALLED
        // Calling again is allowed: patients do not always hear their number the first time.
        CALLED -> next == CALLED || next == COLLECTED || next == NO_SHOW
        COLLECTED, OUT_OF_STOCK, REJECTED, NO_SHOW -> false
    }
}

data class Prescription(
    val id: PrescriptionId,
    /** Short number shown in the waiting room and called out at the counter. */
    val ticketNumber: Int,
    val patientName: String,
    val items: List<PrescriptionItem>,
    val status: PrescriptionStatus,
    /** Why the prescription ended up OUT_OF_STOCK, REJECTED or NO_SHOW, in words a patient understands. */
    val statusReason: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** Incremented on every save; used for optimistic locking. */
    val version: Int,
    /** The government prescription this was checked in with, if any: what is used of it once the patient collects. */
    val governmentNumber: String? = null,
) {
    fun stockReserved(at: Instant) = moveTo(PrescriptionStatus.AWAITING_APPROVAL, at)

    fun outOfStock(unavailable: List<PrescriptionItem>, at: Instant) = moveTo(
        PrescriptionStatus.OUT_OF_STOCK,
        at,
        reason = "Not enough stock of ${unavailable.joinToString { it.medicationName }}",
    )

    fun approve(at: Instant) = moveTo(PrescriptionStatus.AWAITING_PACKAGING, at)

    fun reject(reason: String, at: Instant): Prescription {
        // Where the prescription is comes first: a colleague may have moved it already, and that is what staff need to hear.
        if (!status.canMoveTo(PrescriptionStatus.REJECTED)) throw IllegalTransitionException(ticketNumber, status, PrescriptionStatus.REJECTED)
        val why = reason.trim()
        if (why.isEmpty()) throw ValidationException("A reason is required to reject a prescription")
        if (why.length > MAX_REASON_LENGTH) {
            throw ValidationException("The reason is too long: please use at most $MAX_REASON_LENGTH characters")
        }
        return moveTo(PrescriptionStatus.REJECTED, at, why)
    }

    fun markPacked(at: Instant) = moveTo(PrescriptionStatus.READY_FOR_PICKUP, at)

    fun callPatient(at: Instant) = moveTo(PrescriptionStatus.CALLED, at)

    fun handOver(at: Instant) = moveTo(PrescriptionStatus.COLLECTED, at)

    /** Only a number that was called can go unanswered. */
    fun noShow(at: Instant) =
        moveTo(PrescriptionStatus.NO_SHOW, at, reason = "Nobody answered when number $ticketNumber was called")

    private fun moveTo(next: PrescriptionStatus, at: Instant, reason: String? = null): Prescription {
        if (!status.canMoveTo(next)) throw IllegalTransitionException(ticketNumber, status, next)
        return copy(status = next, statusReason = reason, updatedAt = at)
    }

    companion object {
        const val MAX_NAME_LENGTH = 60

        /** The patient reads a rejection's reason on their phone: a sentence or two, not a document. */
        const val MAX_REASON_LENGTH = 200

        /** A patient arrives with a prescription from their doctor. */
        fun checkIn(
            id: PrescriptionId,
            ticketNumber: Int,
            patientName: String,
            items: List<PrescriptionItem>,
            at: Instant,
            governmentNumber: String? = null,
        ): Prescription {
            val name = patientName.trim()
            if (name.isEmpty()) throw ValidationException("Please tell us your name")
            if (name.length > MAX_NAME_LENGTH) throw ValidationException("Name is too long")
            if (items.isEmpty()) throw ValidationException("A prescription needs at least one medication")
            if (items.map { it.medicationId }.toSet().size != items.size) {
                throw ValidationException("Each medication can only be listed once")
            }
            items.forEach {
                if (it.quantity < 1) {
                    throw ValidationException("Quantity of ${it.medicationName} must be at least 1")
                }
            }
            return Prescription(
                id = id,
                ticketNumber = ticketNumber,
                patientName = name,
                items = items,
                status = PrescriptionStatus.RECEIVED,
                statusReason = null,
                createdAt = at,
                updatedAt = at,
                version = 0,
                governmentNumber = governmentNumber,
            )
        }
    }
}
