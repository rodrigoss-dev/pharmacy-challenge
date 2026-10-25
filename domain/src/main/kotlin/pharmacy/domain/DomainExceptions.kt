package pharmacy.domain

sealed class DomainException(message: String) : RuntimeException(message)

/** The input breaks a business rule; the caller should fix the request. */
class ValidationException(message: String) : DomainException(message)

/**
 * The requested step does not follow from the prescription's current status (e.g. approving twice). Read by staff, who
 * usually hit it because a colleague just did the same step: the message says where the prescription is, not which
 * statuses the code moved between.
 */
class IllegalTransitionException(ticketNumber: Int, from: PrescriptionStatus, to: PrescriptionStatus) :
    DomainException(
        if (from == to) {
            "Prescription #$ticketNumber is already ${from.inWords()}: someone else may have just done this"
        } else {
            "Prescription #$ticketNumber is ${from.inWords()}, so that step is not possible now"
        },
    )

private fun PrescriptionStatus.inWords() = when (this) {
    PrescriptionStatus.RECEIVED -> "waiting for its stock check"
    PrescriptionStatus.AWAITING_APPROVAL -> "waiting for the pharmacist"
    PrescriptionStatus.AWAITING_PACKAGING -> "waiting to be packed"
    PrescriptionStatus.READY_FOR_PICKUP -> "ready for pickup"
    PrescriptionStatus.CALLED -> "called"
    PrescriptionStatus.COLLECTED -> "collected"
    PrescriptionStatus.OUT_OF_STOCK -> "out of stock"
    PrescriptionStatus.REJECTED -> "rejected"
    PrescriptionStatus.NO_SHOW -> "a no show"
}

class NotFoundException(message: String) : DomainException(message)

/**
 * A check-in id that was used already, for other details. Checking in twice with the same id is how a lost response is
 * retried and returns the same ticket; the same id for a different prescription is a mistake, and handing back the first
 * ticket as if it were this one would give the patient medication they did not ask for.
 */
class CheckInIdReusedException :
    DomainException("This check-in was already sent with other details. Please start a new check-in.")

/**
 * The same check-in (same id) committed while this one waited its turn at the ticket counter: a double tap, or a retry
 * that overlapped the attempt it repeats. Not a [DomainException], because nobody is told about it: it rolls this attempt
 * back (the ticket number it took included, so the numbers called out keep no gap) and the caller runs it again, which
 * finds the first and hands back its ticket.
 */
class CheckInRacedException : RuntimeException("This check-in was committed by another request while this one waited for its ticket number")

/** The government prescription system cannot be asked right now. The patient can type their medication instead. */
class GovernmentSystemUnavailableException :
    DomainException("We can't reach the government prescription system right now. Please try again in a moment, or enter your medication by hand.")

/**
 * Two people acted on the same prescription at the same time. Read by staff like [IllegalTransitionException], so it
 * names the ticket they see on screen, not the prescription's id.
 */
class ConcurrentUpdateException(ticketNumber: Int) :
    DomainException("Prescription #$ticketNumber was changed by someone else at the same moment, so this step was not saved: check where it is now")
