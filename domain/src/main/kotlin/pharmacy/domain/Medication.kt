package pharmacy.domain

@JvmInline
value class MedicationId(val value: String) {
    override fun toString() = value
}

data class Medication(
    val id: MedicationId,
    val name: String,
    val category: String,
    /** Packs available to new prescriptions (reserved packs are already subtracted). */
    val stock: Int,
)
