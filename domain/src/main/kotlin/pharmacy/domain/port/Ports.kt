package pharmacy.domain.port

import pharmacy.domain.GovernmentCommit
import pharmacy.domain.GovernmentPrescription
import pharmacy.domain.Medication
import pharmacy.domain.MedicationId
import pharmacy.domain.Prescription
import pharmacy.domain.PrescriptionId
import pharmacy.domain.PrescriptionStatus
import pharmacy.domain.PrescriptionStatusChanged
import java.time.Instant

// Everything the use cases need from the outside world. Implemented in the infra module.

interface PrescriptionRepository {
    fun add(prescription: Prescription)

    /** Saves a status change. Throws [pharmacy.domain.ConcurrentUpdateException] if someone else saved it first. */
    fun update(prescription: Prescription)

    fun findById(id: PrescriptionId): Prescription?

    /** Oldest ticket first, i.e. the order in which the work should be done. */
    fun findByStatus(statuses: Collection<PrescriptionStatus>): List<Prescription>

    /** How many prescriptions entered [status] at [since] or earlier, and are still in it. */
    fun countInStatusSince(status: PrescriptionStatus, since: Instant): Int

    /** How many prescriptions with the same status were checked in before this one. */
    fun countAhead(prescription: Prescription): Int

    /**
     * The next ticket number. Also the point check-ins queue at: it takes a lock on the counter that is held until the
     * transaction ends, so once it returns, every check-in before this one has committed or rolled back, and what is read
     * after it is as they left it.
     */
    fun nextTicketNumber(): Int

    /**
     * Packs of each medication that tickets on the government prescription [governmentNumber] hold, and that the
     * government system does not know of: tickets still in progress (they will be collected), and collected ones whose
     * commit it has not accepted yet, or accepted at [since] or later (after it was asked). Tickets that ended without
     * the medication leaving the shelf (out of stock, rejected, no show) hold nothing.
     */
    fun promisedOn(governmentNumber: String, since: Instant): Map<MedicationId, Int>
}

interface InventoryRepository {
    fun findAll(): List<Medication>

    fun findByIds(ids: Collection<MedicationId>): List<Medication>

    /** Atomically takes [quantity] packs out of stock. Returns false, changing nothing, if there are not enough. */
    fun tryReserve(id: MedicationId, quantity: Int): Boolean

    fun addStock(id: MedicationId, quantity: Int)

    /**
     * Takes the lock every stock check runs under, until the transaction ends: one stock check at a time, on every
     * instance, so that none can overtake another and take the pack meant for an earlier ticket. Waits while another
     * stock check holds it.
     */
    fun lockForStockChecks()
}

/** Records events to be published to the message broker. */
interface EventOutbox {
    fun add(event: PrescriptionStatusChanged)
}

/** The government's prescription system, which lives outside the pharmacy. */
interface GovernmentPrescriptions {
    /**
     * What is left of the prescription with this number, or null if the government has none like it.
     * Throws [pharmacy.domain.GovernmentSystemUnavailableException] if it cannot be asked.
     */
    fun find(number: String): GovernmentPrescription?
}

/**
 * Records what the government system must be told about collected prescriptions. The hand-over adds the commit to the
 * database, in its own transaction, but never calls the government system: that is done afterwards, from what was
 * recorded, so a government outage can neither hold up a patient nor lose a commit.
 */
interface GovernmentCommitOutbox {
    fun add(commit: GovernmentCommit)
}
