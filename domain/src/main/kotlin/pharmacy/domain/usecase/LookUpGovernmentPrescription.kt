package pharmacy.domain.usecase

import pharmacy.domain.DomainLog
import pharmacy.domain.GovernmentPrescription
import pharmacy.domain.NotFoundException
import pharmacy.domain.ValidationException
import pharmacy.domain.port.GovernmentPrescriptions

/**
 * The patient types the number of their government prescription, and gets what they may collect with it. Asked of the
 * government system every time, never kept: what is left changes whenever any pharmacy hands something over.
 */
class LookUpGovernmentPrescription(private val government: GovernmentPrescriptions) {
    private val log = DomainLog.of<LookUpGovernmentPrescription>()

    operator fun invoke(number: String): GovernmentPrescription {
        val typed = number.trim()
        if (typed.isEmpty()) throw ValidationException("Please enter the number of your government prescription")
        if (typed.length > MAX_NUMBER_LENGTH) {
            throw ValidationException("A government prescription number has at most $MAX_NUMBER_LENGTH characters: please check yours")
        }
        val found = government.find(typed) ?: throw NotFoundException(
            "We could not find a government prescription with number $typed. Please check the number.",
        ).also { log.info { "Government prescription $typed looked up: not found" } }

        // Used-up medication stays on what is returned, so that a check-in asking for it is told it is used up, rather than
        // that it is not on the prescription. What is shown to the patient leaves it out (see `toResponse` in infra).
        val left = found.items.count { it.quantity > 0 }
        if (left == 0) {
            log.info { "Government prescription ${found.number} looked up: everything on it was used already" }
            throw ValidationException("Government prescription ${found.number} has already been used in full")
        }
        log.info { "Government prescription ${found.number} looked up: $left medication(s) left" }
        return found
    }

    private companion object {
        const val MAX_NUMBER_LENGTH = 40
    }
}
