package pharmacy.domain.usecase

import pharmacy.domain.DomainLog
import pharmacy.domain.GovernmentCommit
import pharmacy.domain.Prescription
import pharmacy.domain.PrescriptionId
import pharmacy.domain.port.GovernmentCommitOutbox
import pharmacy.domain.port.InventoryRepository
import pharmacy.domain.summary
import java.time.Clock

/** What pharmacists and packagers do to move a prescription through their queues. */
class StaffActions(
    private val updater: PrescriptionUpdater,
    private val inventory: InventoryRepository,
    private val clock: Clock,
    private val reserveStock: ReserveStock,
    private val governmentCommits: GovernmentCommitOutbox,
) {
    private val log = DomainLog.of<StaffActions>()

    /** Pharmacist queue: approve and route to the packaging queue. */
    fun approve(id: PrescriptionId): Prescription = updater.update(id) { it.approve(clock.now()) }

    /** Pharmacist queue: reject and put the reserved packs back on the shelf. */
    fun reject(id: PrescriptionId, reason: String): Prescription = updater.update(id) { prescription ->
        prescription.reject(reason, clock.now()).also { putBack(prescription, "Rejected") }
    }

    /** Packaging queue: packed and handed back to the pharmacist. */
    fun markPacked(id: PrescriptionId): Prescription = updater.update(id) { it.markPacked(clock.now()) }

    /** Pickup counter: call out the patient's number (again, if needed). */
    fun callPatient(id: PrescriptionId): Prescription = updater.update(id) { it.callPatient(clock.now()) }

    /**
     * Pickup counter: the patient has their medication. If it came with a government prescription, what the patient
     * took is queued to be committed to the government system, in the same transaction as the hand-over: it is sent
     * afterwards, so a government outage never holds up the counter, and nothing handed over is ever left unreported.
     */
    fun handOver(id: PrescriptionId): Prescription = updater.update(id) { it.handOver(clock.now()) }.also { collected ->
        GovernmentCommit.of(collected)?.let { commit ->
            governmentCommits.add(commit)
            log.info {
                "#${collected.ticketNumber}: ${collected.items.summary()} to be committed to government prescription " +
                    commit.governmentNumber
            }
        }
    }

    /**
     * Pickup counter: the patient did not answer when their number was called. The prescription ends here, and the
     * packs reserved for it go back on the shelf for the next patient. Nothing is committed to the government system:
     * the patient took nothing.
     */
    fun noShow(id: PrescriptionId): Prescription = updater.update(id) { prescription ->
        prescription.noShow(clock.now()).also { putBack(prescription, "No show") }
    }

    /** For prescriptions stuck in RECEIVED, e.g. while the message broker is down: run the stock check right now. */
    fun retryStockCheck(id: PrescriptionId): Prescription {
        log.info { "Stock check for $id retried by hand from the staff console" }
        return reserveStock(id)
    }

    private fun putBack(prescription: Prescription, because: String) {
        prescription.items.forEach { inventory.addStock(it.medicationId, it.quantity) }
        log.info { "$because #${prescription.ticketNumber}: put back ${prescription.items.summary()}" }
    }
}
