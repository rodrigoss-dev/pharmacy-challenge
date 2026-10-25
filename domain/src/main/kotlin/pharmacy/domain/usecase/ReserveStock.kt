package pharmacy.domain.usecase

import pharmacy.domain.DomainLog
import pharmacy.domain.Prescription
import pharmacy.domain.PrescriptionId
import pharmacy.domain.PrescriptionStatus
import pharmacy.domain.port.InventoryRepository
import pharmacy.domain.port.PrescriptionRepository
import pharmacy.domain.summary
import java.time.Clock

/**
 * Verifies the inventory for a received prescription and reserves the packs, then routes it to the pharmacist queue
 * (or marks it out of stock). Triggered by the `prescription.received` message, by the sweeper that catches stock
 * checks whose message never came, or manually by staff.
 *
 * First come, first served: stock checks run one at a time, and each first checks everyone who checked in before this
 * patient and is still waiting, oldest ticket first. However the messages, the sweeper, a staff retry and several
 * instances race each other, the last pack goes to the earliest ticket.
 *
 * Idempotent: messages are delivered at least once, so anything not in RECEIVED is left untouched.
 */
class ReserveStock(
    private val updater: PrescriptionUpdater,
    private val prescriptions: PrescriptionRepository,
    private val inventory: InventoryRepository,
    private val clock: Clock,
) {
    private val log = DomainLog.of<ReserveStock>()

    operator fun invoke(id: PrescriptionId): Prescription {
        // Before anything is read, so that what is read stays true until the transaction ends.
        inventory.lockForStockChecks()

        val waiting = prescriptions.findByStatus(listOf(PrescriptionStatus.RECEIVED))
        val patient = waiting.firstOrNull { it.id == id }
        if (patient != null) {
            val ahead = waiting.filter { it.ticketNumber < patient.ticketNumber }
            if (ahead.isNotEmpty()) {
                log.info {
                    "Stock check for #${patient.ticketNumber}: ${ahead.joinToString { "#${it.ticketNumber}" }} checked in " +
                        "earlier and still wait${if (ahead.size == 1) "s" else ""}, so ${if (ahead.size == 1) "it is" else "they are"} checked first"
                }
                ahead.forEach { check(it.id) }
            }
        }
        return check(id)
    }

    private fun check(id: PrescriptionId): Prescription = updater.update(id) { prescription ->
        if (prescription.status != PrescriptionStatus.RECEIVED) {
            log.info {
                "Stock check for #${prescription.ticketNumber} skipped: already ${prescription.status} " +
                    "(a repeated message, or another stock check got there first)"
            }
            return@update prescription
        }

        // tryReserve has a side effect: each item is reserved as it is checked.
        val (reserved, unavailable) = prescription.items.partition { inventory.tryReserve(it.medicationId, it.quantity) }
        if (unavailable.isEmpty()) {
            log.info { "Stock check for #${prescription.ticketNumber}: reserved ${reserved.summary()}" }
            prescription.stockReserved(clock.now())
        } else {
            // All or nothing: give back what was reserved for this prescription.
            reserved.forEach { inventory.addStock(it.medicationId, it.quantity) }
            log.warn {
                "Stock check for #${prescription.ticketNumber}: not enough stock for ${unavailable.summary()}" +
                    (if (reserved.isEmpty()) "" else "; put back ${reserved.summary()}") + ". Consider restocking."
            }
            prescription.outOfStock(unavailable, clock.now())
        }
    }
}
