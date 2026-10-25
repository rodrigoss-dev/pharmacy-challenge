package pharmacy.domain.usecase

import pharmacy.domain.ConcurrentUpdateException
import pharmacy.domain.DomainException
import pharmacy.domain.DomainLog
import pharmacy.domain.NotFoundException
import pharmacy.domain.Prescription
import pharmacy.domain.PrescriptionId
import pharmacy.domain.PrescriptionStatusChanged
import pharmacy.domain.port.EventOutbox
import pharmacy.domain.port.PrescriptionRepository
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Load, change, save and record the event. Shared by the use cases that move a prescription, so every status change
 * (and every refused one) is logged here, in one format: `#17 RECEIVED -> AWAITING_APPROVAL`.
 */
class PrescriptionUpdater(
    private val prescriptions: PrescriptionRepository,
    private val outbox: EventOutbox,
) {
    private val log = DomainLog.of<PrescriptionUpdater>()

    fun update(id: PrescriptionId, change: (Prescription) -> Prescription): Prescription {
        val current = prescriptions.findById(id) ?: throw prescriptionNotFound(id).also { log.info { it.message!! } }
        val updated = try {
            change(current)
        } catch (e: DomainException) {
            log.info { "#${current.ticketNumber} (${current.status}): refused, ${e.message}" }
            throw e
        }
        if (updated == current) {
            log.debug { "#${current.ticketNumber}: nothing to change, still ${current.status}" }
            return current
        }
        try {
            prescriptions.update(updated)
        } catch (e: ConcurrentUpdateException) {
            log.warn {
                "#${current.ticketNumber}: changed by someone else while moving it from ${current.status} to " +
                    "${updated.status} (it was at version ${current.version}); this change is not saved"
            }
            throw e
        }
        outbox.add(PrescriptionStatusChanged.of(updated))
        log.info {
            "#${updated.ticketNumber} ${current.status} -> ${updated.status}" +
                (updated.statusReason?.let { " (reason: $it)" } ?: "")
        }
        return updated
    }
}

internal fun prescriptionNotFound(id: PrescriptionId) = NotFoundException("Prescription $id not found")

/** Timestamps are stored with millisecond precision; truncating keeps saved and in-memory values equal. */
internal fun Clock.now(): Instant = instant().truncatedTo(ChronoUnit.MILLIS)
