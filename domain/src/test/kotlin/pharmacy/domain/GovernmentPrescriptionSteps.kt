package pharmacy.domain

import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Steps for government-prescription.feature. Cucumber creates a new instance per scenario. */
class GovernmentPrescriptionSteps {
    private val now = Instant.parse("2026-10-23T10:00:00Z")
    private var government: GovernmentPrescription? = null
    private var prescription: Prescription? = null
    private var refusal: ValidationException? = null

    /** Packs other tickets on the prescription hold and the government system does not know of yet. */
    private val promised = mutableMapOf<MedicationId, Int>()

    @Given("the government prescription {word} lists:")
    fun governmentPrescription(number: String, rows: List<Map<String, String>>) {
        government = GovernmentPrescription(
            number,
            rows.map {
                GovernmentPrescription.Item(
                    medicationId = id(it.getValue("medication")),
                    quantity = it.getValue("packs").toInt(),
                    maximum = it.getValue("maximum") == "yes",
                )
            },
            readAt = now,
        )
    }

    @Given("{int} packs of {medication} are already promised on it")
    fun promisedAlready(packs: Int, medication: String) {
        promised[id(medication)] = packs
    }

    @Given("all of the {medication} on it was collected already")
    fun collectedAlready(medication: String) {
        val listed = checkNotNull(government) { "No government prescription in this scenario yet" }
        government = listed.copy(items = listed.items.map { if (it.medicationId == id(medication)) it.copy(quantity = 0) else it })
    }

    // Check-in

    @When("the patient checks in on it with {int} packs of {medication}")
    fun checkInOnItWithOne(packs: Int, medication: String) = attempt { checkIn(listOf(item(medication, packs)), government) }

    @Given("the patient checked in on it with {int} packs of {medication}")
    fun checkedInOnItWithOne(packs: Int, medication: String) = checkIn(listOf(item(medication, packs)), government).let { prescription = it }

    @Given("the patient checked in on it with:")
    fun checkedInOnItWith(items: List<PrescriptionItem>) = checkIn(items, government).let { prescription = it }

    @Given("the patient checked in without a government prescription, with {int} packs of {medication}")
    fun checkedInWithoutIt(packs: Int, medication: String) = checkIn(listOf(item(medication, packs)), government = null).let { prescription = it }

    @Then("the check-in is accepted")
    fun accepted() {
        refusal?.let { throw AssertionError("The check-in was refused: ${it.message}", it) }
    }

    @Then("the check-in is refused with {string}")
    fun refused(message: String) = assertEquals(message, refusal?.message ?: "(accepted)")

    // Collecting

    @When("the patient collects it")
    fun collects() {
        prescription = checkNotNull(prescription) { "No prescription in this scenario yet" }
            .stockReserved(now).approve(now).markPacked(now).callPatient(now).handOver(now)
    }

    @Then("this is committed to {word}:")
    fun committed(number: String, expected: List<PrescriptionItem>) {
        val commit = checkNotNull(GovernmentCommit.of(prescription!!)) { "Nothing is committed" }
        assertEquals(number, commit.governmentNumber)
        assertEquals(prescription!!.id, commit.prescriptionId)
        assertEquals(expected.map { it.medicationId to it.quantity }, commit.items.map { it.medicationId to it.quantity })
    }

    @Then("nothing is committed")
    fun nothingCommitted() = assertNull(GovernmentCommit.of(prescription!!))

    // Check-in goes through the same rules as SubmitPrescription: the prescription's own, then the government's.
    private fun checkIn(items: List<PrescriptionItem>, government: GovernmentPrescription?): Prescription {
        val checkedIn = Prescription.checkIn(PrescriptionId.random(), 7, "Maria", items, now, government?.number)
        government?.check(items, promised)
        return checkedIn
    }

    private fun attempt(step: () -> Prescription) {
        try {
            prescription = step()
        } catch (e: ValidationException) {
            refusal = e
        }
    }

    private fun id(medication: String) = MedicationId(medication.substringBefore(' ').lowercase())

    private fun item(medication: String, packs: Int) = PrescriptionItem(id(medication), medication, packs)
}
