package pharmacy.domain.usecase

import pharmacy.domain.CheckInIdReusedException
import pharmacy.domain.CheckInRacedException
import pharmacy.domain.DomainLog
import pharmacy.domain.GovernmentPrescription
import pharmacy.domain.MedicationId
import pharmacy.domain.Prescription
import pharmacy.domain.PrescriptionId
import pharmacy.domain.PrescriptionItem
import pharmacy.domain.PrescriptionStatusChanged
import pharmacy.domain.ValidationException
import pharmacy.domain.port.EventOutbox
import pharmacy.domain.port.InventoryRepository
import pharmacy.domain.port.PrescriptionRepository
import pharmacy.domain.summary
import java.time.Clock

/** Patient checks in with their prescription and gets a ticket number. The stock check happens asynchronously. */
class SubmitPrescription(
    private val prescriptions: PrescriptionRepository,
    private val inventory: InventoryRepository,
    private val outbox: EventOutbox,
    private val clock: Clock,
) {
    private val log = DomainLog.of<SubmitPrescription>()

    data class Command(
        /** Optional id chosen by the client, so that retrying after a lost response does not check in twice. */
        val id: PrescriptionId?,
        val patientName: String,
        val items: List<Item>,
        /** Set when the patient checks in with a government prescription: what they pick must be covered by it. */
        val government: GovernmentPrescription? = null,
    ) {
        data class Item(val medicationId: MedicationId, val quantity: Int)
    }

    /** Whether a check-in with this client-chosen id went through already, so that sending it again changes nothing. */
    fun isCheckedIn(id: PrescriptionId): Boolean = prescriptions.findById(id) != null

    operator fun invoke(command: Command): Prescription {
        val existing = command.id?.let(prescriptions::findById)
        if (existing != null) return sentAgain(command, existing)

        val medications = inventory.findByIds(command.items.map { it.medicationId }).associateBy { it.id }
        val items = command.items.map {
            val medication = medications[it.medicationId]
                ?: throw refused(ValidationException("Unknown medication '${it.medicationId}'"))
            PrescriptionItem(medication.id, medication.name, it.quantity)
        }
        val prescription = try {
            // The prescription's own rules first, with a ticket number that is not drawn yet: a check-in that cannot be
            // accepted must not queue for the counter.
            val draft = Prescription.checkIn(
                id = command.id ?: PrescriptionId.random(),
                ticketNumber = 0,
                patientName = command.patientName,
                items = items,
                at = clock.now(),
                governmentNumber = command.government?.number,
            )
            // Then the ticket number, before anything else is read: every check-in queues here, so what the next lines
            // read about the other tickets on a government prescription is as the check-ins before this one left it.
            val ticketNumber = prescriptions.nextTicketNumber()
            // The first look for this id, above, was before the queue: a check-in with the same id that was ahead of this
            // one has committed since. It must not be judged against the packs its own ticket holds.
            if (command.id != null && prescriptions.findById(command.id) != null) throw CheckInRacedException()
            command.government?.let { government ->
                government.check(items, promised = prescriptions.promisedOn(government.number, government.readAt))
            }
            draft.copy(ticketNumber = ticketNumber)
        } catch (e: ValidationException) {
            throw refused(e)
        }
        prescriptions.add(prescription)
        outbox.add(PrescriptionStatusChanged.of(prescription))
        log.info {
            "Checked in #${prescription.ticketNumber} (${prescription.id}" +
                (if (command.id == null) ", id chosen by the server" else "") + "): ${prescription.items.summary()}" +
                (prescription.governmentNumber?.let { ", on government prescription $it" } ?: "")
        }
        return prescription
    }

    /** A retry (lost response, a second tap) gets its ticket back; the same id with other details is refused. */
    private fun sentAgain(command: Command, existing: Prescription): Prescription {
        val asked = command.items.map { it.medicationId.value to it.quantity }.sortedBy { it.first }
        val booked = existing.items.map { it.medicationId.value to it.quantity }.sortedBy { it.first }
        if (existing.patientName != command.patientName.trim() || asked != booked) {
            log.info { "Check-in ${command.id} refused: it was used already for #${existing.ticketNumber}, with other details" }
            throw CheckInIdReusedException()
        }
        log.info {
            "Check-in ${command.id} sent again (lost response or a second tap): returning the existing ticket " +
                "#${existing.ticketNumber}, now ${existing.status}"
        }
        return existing
    }

    private fun refused(e: ValidationException): ValidationException {
        log.info { "Check-in refused: ${e.message}" }
        return e
    }
}
