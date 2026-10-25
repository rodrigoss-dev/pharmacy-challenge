package pharmacy.infra.api

import jakarta.ws.rs.BadRequestException
import pharmacy.domain.GovernmentPrescription
import pharmacy.domain.Medication
import pharmacy.domain.MedicationId
import pharmacy.domain.Prescription
import pharmacy.domain.PrescriptionId
import pharmacy.domain.PrescriptionItem
import pharmacy.domain.ValidationException
import pharmacy.domain.usecase.Board
import pharmacy.domain.usecase.PrescriptionTracking
import pharmacy.domain.usecase.SubmitPrescription
import pharmacy.domain.usecase.WorkQueues
import java.util.UUID

// HTTP request/response bodies (JSON through Jackson), and their mapping from the domain's models.

data class CheckInRequest(
    /** Optional client-generated UUID; resending the same id returns the existing ticket instead of a new one. */
    val id: String? = null,
    // Nullable, so that a null (or a missing value) reaches the business rules, which answer in words a patient reads.
    val patientName: String? = null,
    val items: List<CheckInItem?>? = null,
    /** Set when the items come from a government prescription (see GET /api/government-prescriptions/{number}). */
    val governmentNumber: String? = null,
) {
    fun toCommand(government: GovernmentPrescription? = null) = SubmitPrescription.Command(
        id = id?.toPrescriptionId(),
        patientName = patientName.orEmpty(),
        items = items.orEmpty().map {
            val item = it ?: throw ValidationException("Each medication needs a medication id and a number of packs")
            SubmitPrescription.Command.Item(MedicationId(item.medicationId), item.quantity)
        },
        government = government,
    )
}

data class CheckInItem(val medicationId: String, val quantity: Int)

data class GovernmentItemResponse(
    val medicationId: String,
    /** Packs left to collect. */
    val quantity: Int,
    /** True: take anything from 1 up to [quantity]. False: [quantity] is what was prescribed, taken as it is. */
    val maximum: Boolean,
)

data class GovernmentPrescriptionResponse(val number: String, val items: List<GovernmentItemResponse>)

data class CheckInResponse(val id: String, val ticketNumber: Int)

data class MedicationResponse(val id: String, val name: String, val category: String, val stock: Int)

data class ItemResponse(val medicationId: String, val name: String, val quantity: Int)

data class TrackingResponse(
    val id: String,
    val ticketNumber: Int,
    val patientName: String,
    val status: String,
    val statusReason: String?,
    val items: List<ItemResponse>,
    val peopleAhead: Int,
    val delayed: Boolean,
    val checkedInAt: String,
    val updatedAt: String,
)

data class BoardResponse(val nowCalling: List<Int>, val ready: List<Int>, val inProgress: List<Int>)

data class StaffPrescriptionResponse(
    val id: String,
    val ticketNumber: Int,
    val patientName: String,
    val status: String,
    val statusReason: String?,
    val items: List<ItemResponse>,
    val checkedInAt: String,
    val updatedAt: String,
    /** The government prescription the patient checked in with, if any: the pharmacist checks it at the counter. */
    val governmentNumber: String?,
)

data class WorkQueuesResponse(
    val stockCheck: List<StaffPrescriptionResponse>,
    val pharmacist: List<StaffPrescriptionResponse>,
    val packaging: List<StaffPrescriptionResponse>,
    val pickup: List<StaffPrescriptionResponse>,
)

// Nullable, like the check-in: a missing or null value gets the answer written for it, not Jackson's idea of a default.
data class RejectRequest(val reason: String? = null)

data class RestockRequest(val quantity: Int? = null)

data class HealthReport(
    /** "up" or "down". While down nothing can be saved: requests get a 503, the screens say updates are delayed. */
    val database: String,
    /** "up" or "down". While down, check-ins and staff actions keep working; events wait in the outbox. */
    val broker: String,
    /** Why RabbitMQ refuses new messages (memory or disk alarm); null when it does not. */
    val brokerBlocked: String?,
    /** Events written but not yet confirmed by RabbitMQ; null while the database is down. */
    val pendingEvents: Int?,
    /** How long the oldest of those has waited; null when there are none. */
    val oldestPendingEventSeconds: Long?,
    /** Prescriptions waiting longer than they should for their stock check (they are retried automatically). */
    val stalledStockChecks: Int?,
    /** Stock check messages parked in the dead-letter queue; null when the broker cannot be asked. */
    val deadLetters: Long?,
    /** Commits to the government system not accepted by it yet (it is down, or slow); null while the database is down. */
    val pendingGovernmentCommits: Int?,
    /** How long the oldest of those has waited; null when there are none. A few seconds is normal, minutes is not. */
    val oldestPendingGovernmentCommitSeconds: Long?,
    /** Commits the government refused for good, which only a person can sort out; null while the database is down. */
    val rejectedGovernmentCommits: Int?,
    /** Live screens (patient pages, boards) connected to this instance. */
    val liveStreams: Int,
)

data class ErrorResponse(val error: String)

fun String.toPrescriptionId(): PrescriptionId =
    runCatching { PrescriptionId(UUID.fromString(this)) }.getOrElse { throw BadRequestException("Invalid prescription id") }

fun Medication.toResponse() = MedicationResponse(id.value, name, category, stock)

fun PrescriptionItem.toResponse() = ItemResponse(medicationId.value, medicationName, quantity)

fun PrescriptionTracking.toResponse() = TrackingResponse(
    id = prescription.id.toString(),
    ticketNumber = prescription.ticketNumber,
    patientName = prescription.patientName,
    status = prescription.status.name,
    statusReason = prescription.statusReason,
    items = prescription.items.map { it.toResponse() },
    peopleAhead = peopleAhead,
    delayed = delayed,
    checkedInAt = prescription.createdAt.toString(),
    updatedAt = prescription.updatedAt.toString(),
)

fun Board.toResponse() = BoardResponse(nowCalling, ready, inProgress)

fun Prescription.toStaffResponse() = StaffPrescriptionResponse(
    id = id.toString(),
    ticketNumber = ticketNumber,
    patientName = patientName,
    status = status.name,
    statusReason = statusReason,
    items = items.map { it.toResponse() },
    checkedInAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
    governmentNumber = governmentNumber,
)

/** What the patient may still collect: what was used up in full is not on offer any more. */
fun GovernmentPrescription.toResponse() = GovernmentPrescriptionResponse(
    number,
    items.filter { it.quantity > 0 }.map { GovernmentItemResponse(it.medicationId.value, it.quantity, it.maximum) },
)

fun WorkQueues.toResponse() = WorkQueuesResponse(
    stockCheck = stockCheck.map { it.toStaffResponse() },
    pharmacist = pharmacist.map { it.toStaffResponse() },
    packaging = packaging.map { it.toStaffResponse() },
    pickup = pickup.map { it.toStaffResponse() },
)
