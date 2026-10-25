package pharmacy.infra.messaging

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import pharmacy.domain.PrescriptionId
import pharmacy.domain.PrescriptionStatus
import pharmacy.domain.PrescriptionStatusChanged
import java.time.Instant
import java.util.UUID

/** Wire format of [PrescriptionStatusChanged]. Kept separate from the domain event so either can evolve. */
internal data class StatusChangedMessage(
    val eventId: String,
    val prescriptionId: String,
    val ticketNumber: Int,
    val status: String,
    val occurredAt: String,
)

object StatusChangedMessages {
    private val json = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    /** e.g. `prescription.awaiting_approval`, so consumers can bind to exactly the steps they care about. */
    fun routingKey(status: PrescriptionStatus) = "prescription.${status.name.lowercase()}"

    fun encode(event: PrescriptionStatusChanged): String = json.writeValueAsString(
        StatusChangedMessage(
            eventId = event.eventId.toString(),
            prescriptionId = event.prescriptionId.toString(),
            ticketNumber = event.ticketNumber,
            status = event.status.name,
            occurredAt = event.occurredAt.toString(),
        ),
    )

    fun decode(body: ByteArray): PrescriptionStatusChanged {
        val message = json.readValue<StatusChangedMessage>(body)
        return PrescriptionStatusChanged(
            eventId = UUID.fromString(message.eventId),
            prescriptionId = PrescriptionId(UUID.fromString(message.prescriptionId)),
            ticketNumber = message.ticketNumber,
            status = PrescriptionStatus.valueOf(message.status),
            occurredAt = Instant.parse(message.occurredAt),
        )
    }
}
