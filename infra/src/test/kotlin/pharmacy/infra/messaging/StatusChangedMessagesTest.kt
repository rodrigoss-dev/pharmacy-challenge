package pharmacy.infra.messaging

import pharmacy.domain.PrescriptionId
import pharmacy.domain.PrescriptionStatus
import pharmacy.domain.PrescriptionStatusChanged
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class StatusChangedMessagesTest {
    @Test
    fun `round-trips through the wire format`() {
        val event = PrescriptionStatusChanged(
            eventId = UUID.randomUUID(),
            prescriptionId = PrescriptionId.random(),
            ticketNumber = 42,
            status = PrescriptionStatus.AWAITING_PACKAGING,
            occurredAt = Instant.parse("2026-10-23T10:15:30.123Z"),
        )

        assertEquals(event, StatusChangedMessages.decode(StatusChangedMessages.encode(event).toByteArray()))
        assertEquals("prescription.awaiting_packaging", StatusChangedMessages.routingKey(event.status))
    }
}
