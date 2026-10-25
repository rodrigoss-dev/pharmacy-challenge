package pharmacy.infra.government

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import pharmacy.domain.GovernmentCommit
import pharmacy.domain.GovernmentPrescription
import pharmacy.domain.MedicationId
import java.time.Instant

/**
 * Wire formats: what the government API speaks, and the items of a commit as the outbox stores them. Kept apart from
 * the domain's models so either side can evolve.
 */
internal object GovernmentMessages {
    private val json = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    // GET /prescriptions/{number}
    private data class PrescriptionBody(val number: String, val items: List<PrescriptionItemBody>)

    private data class PrescriptionItemBody(val productId: String, val quantity: Int, val maximum: Boolean = false)

    fun decodePrescription(body: String, readAt: Instant): GovernmentPrescription {
        val prescription = json.readValue<PrescriptionBody>(body)
        return GovernmentPrescription(
            prescription.number,
            prescription.items.map { GovernmentPrescription.Item(MedicationId(it.productId), it.quantity, it.maximum) },
            readAt,
        )
    }

    // POST /prescriptions/{number}/commits
    private data class CommitItemBody(val productId: String, val quantity: Int)

    private data class CommitBody(val commitId: String, val items: List<CommitItemBody>)

    private data class ErrorBody(val error: String? = null)

    fun encodeCommit(commit: GovernmentCommit): String = json.writeValueAsString(
        CommitBody(commit.prescriptionId.toString(), commit.items.map { CommitItemBody(it.medicationId.value, it.quantity) }),
    )

    /** The government's explanation of a refusal, or null if it gave none we can read. */
    fun decodeError(body: String): String? = runCatching { json.readValue<ErrorBody>(body).error }.getOrNull()

    // The items column of government_commits
    private data class StoredItem(val medicationId: String, val quantity: Int)

    fun encodeItems(items: List<GovernmentCommit.Item>): String =
        json.writeValueAsString(items.map { StoredItem(it.medicationId.value, it.quantity) })

    fun decodeItems(stored: String): List<GovernmentCommit.Item> =
        json.readValue<List<StoredItem>>(stored).map { GovernmentCommit.Item(MedicationId(it.medicationId), it.quantity) }
}
