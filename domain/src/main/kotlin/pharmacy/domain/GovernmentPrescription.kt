package pharmacy.domain

import java.time.Instant

/**
 * What the government system holds for a prescription number: the medication a patient may collect, and how much of
 * it is left. Loaded when the patient checks in, so they pick from it instead of typing their medication.
 */
data class GovernmentPrescription(
    /** As the government system writes it (the patient may have typed it in another case). */
    val number: String,
    /** Everything on the prescription, including what was used up already ([Item.quantity] 0): [check] says so. */
    val items: List<Item>,
    /**
     * When the government system was asked. What it had accepted from this pharmacy by then is in [items]; a commit it
     * accepted after that is not (see [pharmacy.domain.port.PrescriptionRepository.promisedOn]).
     */
    val readAt: Instant,
) {
    data class Item(
        val medicationId: MedicationId,
        /** Packs left to collect. */
        val quantity: Int,
        /**
         * True: [quantity] is a ceiling, the patient may take anything from 1 pack up to it.
         * False: [quantity] is what the doctor prescribed, and it is taken as it is.
         */
        val maximum: Boolean,
    ) {
        fun allows(packs: Int) = if (maximum) packs in 1..quantity else packs == quantity
    }

    /**
     * Refuses what the patient asks for if the government prescription does not cover it. [promised] is what other
     * tickets on this prescription hold already and the government has not been told of yet (two phones, one number):
     * it is not available twice.
     */
    fun check(requested: List<PrescriptionItem>, promised: Map<MedicationId, Int> = emptyMap()) {
        requested.forEach { asked ->
            val listed = items.firstOrNull { it.medicationId == asked.medicationId }
                ?: throw ValidationException("${asked.medicationName} is not on government prescription $number")
            if (listed.quantity <= 0) {
                throw ValidationException("Government prescription $number has no ${asked.medicationName} left to collect")
            }
            val taken = promised[asked.medicationId] ?: 0
            val covered = listed.copy(quantity = listed.quantity - taken)
            val elsewhere = if (taken > 0) " (${packs(taken)} of it already on a ticket that is still in progress)" else ""
            if (covered.quantity <= 0) {
                throw ValidationException("Government prescription $number has no ${asked.medicationName} left to collect$elsewhere")
            }
            if (!covered.allows(asked.quantity)) {
                throw ValidationException(
                    if (covered.maximum) {
                        "Government prescription $number allows at most ${packs(covered.quantity)} of ${asked.medicationName}$elsewhere"
                    } else {
                        "Government prescription $number covers ${packs(covered.quantity)} of ${asked.medicationName}, no more and no less$elsewhere"
                    },
                )
            }
        }
    }

    private fun packs(count: Int) = if (count == 1) "1 pack" else "$count packs"
}

/**
 * What the government system must be told once the patient has their medication: how much of each was used, so the
 * next visit finds only what is left. Taken from the prescription as it was handed over: a patient who took 1 pack of a
 * medication with a maximum of 2 uses 1.
 */
data class GovernmentCommit(
    /** Also the key the government system recognizes a repeated commit by: one commit per prescription, ever. */
    val prescriptionId: PrescriptionId,
    val governmentNumber: String,
    val items: List<Item>,
) {
    data class Item(val medicationId: MedicationId, val quantity: Int)

    companion object {
        /**
         * Null unless the patient has collected a prescription that came with a government prescription. Anything else
         * has nothing to tell the government: not yet (still in the queues), or ever (rejected, out of stock, typed by hand).
         */
        fun of(prescription: Prescription): GovernmentCommit? {
            val number = prescription.governmentNumber ?: return null
            if (prescription.status != PrescriptionStatus.COLLECTED) return null
            return GovernmentCommit(prescription.id, number, prescription.items.map { Item(it.medicationId, it.quantity) })
        }
    }
}
