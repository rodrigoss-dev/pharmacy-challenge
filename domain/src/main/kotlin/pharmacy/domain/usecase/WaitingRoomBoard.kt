package pharmacy.domain.usecase

import pharmacy.domain.PrescriptionStatus
import pharmacy.domain.PrescriptionStatus.AWAITING_APPROVAL
import pharmacy.domain.PrescriptionStatus.AWAITING_PACKAGING
import pharmacy.domain.PrescriptionStatus.CALLED
import pharmacy.domain.PrescriptionStatus.READY_FOR_PICKUP
import pharmacy.domain.PrescriptionStatus.RECEIVED
import pharmacy.domain.port.PrescriptionRepository

/** Ticket numbers only: the board hangs in a public waiting room. */
data class Board(
    val nowCalling: List<Int>,
    val ready: List<Int>,
    val inProgress: List<Int>,
)

/** The screen in the waiting room, for patients without a phone at hand. */
class WaitingRoomBoard(private val prescriptions: PrescriptionRepository) {
    operator fun invoke(): Board {
        val active = prescriptions.findByStatus(PrescriptionStatus.entries.filterNot { it.isFinal })
        fun tickets(vararg statuses: PrescriptionStatus) =
            active.filter { it.status in statuses }.map { it.ticketNumber }
        return Board(
            nowCalling = tickets(CALLED),
            ready = tickets(READY_FOR_PICKUP),
            inProgress = tickets(RECEIVED, AWAITING_APPROVAL, AWAITING_PACKAGING),
        )
    }
}
