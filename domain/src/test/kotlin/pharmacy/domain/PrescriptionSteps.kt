package pharmacy.domain

import io.cucumber.java.DataTableType
import io.cucumber.java.ParameterType
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import pharmacy.domain.PrescriptionStatus.AWAITING_APPROVAL
import pharmacy.domain.PrescriptionStatus.AWAITING_PACKAGING
import pharmacy.domain.PrescriptionStatus.CALLED
import pharmacy.domain.PrescriptionStatus.COLLECTED
import pharmacy.domain.PrescriptionStatus.NO_SHOW
import pharmacy.domain.PrescriptionStatus.OUT_OF_STOCK
import pharmacy.domain.PrescriptionStatus.READY_FOR_PICKUP
import pharmacy.domain.PrescriptionStatus.RECEIVED
import pharmacy.domain.PrescriptionStatus.REJECTED
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Steps for the .feature files in src/test/resources/pharmacy/domain. Cucumber creates a new instance per scenario. */
class PrescriptionSteps {
    private val now = Instant.parse("2026-10-23T10:00:00Z")
    private var patientName = "Maria"
    private var prescription: Prescription? = null

    /** Set when a When step breaks a rule. A Then step must expect it, otherwise the scenario fails. */
    private var refusal: DomainException? = null

    @ParameterType("[A-Z_]+")
    fun status(name: String) = PrescriptionStatus.valueOf(name)

    /** As named in the catalog, e.g. "Amoxicillin 500 mg". */
    @ParameterType("[A-Z][a-z]+ \\d+ mg")
    fun medication(name: String) = name

    @DataTableType
    fun prescriptionItem(row: Map<String, String>) = item(row.getValue("medication"), row.getValue("packs").toInt())

    // Check-in

    @Given("a patient named {string}")
    fun patientNamed(name: String) {
        patientName = name
    }

    @Given("a patient whose name is {int} characters long")
    fun patientWithNameLength(length: Int) {
        patientName = "x".repeat(length)
    }

    @When("they check in with {int} pack(s) of {medication}")
    fun checkInWithOne(packs: Int, medication: String) = attempt { checkIn(listOf(item(medication, packs))) }

    @When("they check in with:")
    fun checkInWith(items: List<PrescriptionItem>) = attempt { checkIn(items) }

    @When("they check in with no medication")
    fun checkInWithNothing() = attempt { checkIn(emptyList()) }

    @Then("it is in the name of {string}")
    fun inTheNameOf(name: String) = assertEquals(name, current.patientName)

    @Then("it was last updated at the time of check-in")
    fun updatedAtCheckIn() = assertEquals(now, current.updatedAt)

    @Then("they are told {string}")
    fun patientIsTold(message: String) = assertEquals(message, expectRefusal<ValidationException>().message)

    // Lifecycle

    @Given("a prescription that is {status}")
    fun prescriptionThatIs(status: PrescriptionStatus) {
        prescription = reach(status)
    }

    @Given("a prescription for:")
    fun prescriptionFor(items: List<PrescriptionItem>) {
        prescription = checkIn(items)
    }

    @When("the stock is reserved")
    fun stockReserved() = attempt { current.stockReserved(now) }

    @When("there is not enough stock of {medication}")
    fun notEnoughStockOf(medication: String) =
        attempt { current.outOfStock(current.items.filter { it.medicationName == medication }, now) }

    @When("the pharmacist approves it")
    fun approve() = attempt { current.approve(now) }

    @When("the pharmacist rejects it with the reason {string}")
    fun reject(reason: String) = attempt { current.reject(reason, now) }

    @When("the pharmacist rejects it with a reason of {int} characters")
    fun rejectWithReasonOfLength(length: Int) = attempt { current.reject("x".repeat(length), now) }

    @When("it is packed")
    fun markPacked() = attempt { current.markPacked(now) }

    @When("^the patient's number is called(?: again)?$")
    fun callPatient() = attempt { current.callPatient(now) }

    @When("it is handed over")
    fun handOver() = attempt { current.handOver(now) }

    @When("the patient does not answer")
    fun noShow() = attempt { current.noShow(now) }

    @Then("it is {status}")
    @Then("their prescription is {status}")
    fun hasStatus(status: PrescriptionStatus) = assertEquals(status, current.status)

    @Then("the reason given is {string}")
    fun reasonGiven(reason: String) = assertEquals(reason, current.statusReason)

    @Then("the pharmacist is told {string}")
    fun pharmacistIsTold(message: String) = assertEquals(message, expectRefusal<ValidationException>().message)

    @Then("the staff member is told {string}")
    fun staffMemberIsTold(message: String) = assertEquals(message, expectRefusal<IllegalTransitionException>().message)

    @Then("that is not allowed")
    fun notAllowed() {
        expectRefusal<IllegalTransitionException>()
    }

    @Then("statuses can only move like this:")
    fun onlyTheseMoves(moves: List<Map<String, String>>) {
        val allowed = moves.map { PrescriptionStatus.valueOf(it.getValue("from")) to PrescriptionStatus.valueOf(it.getValue("to")) }.toSet()
        for (from in PrescriptionStatus.entries) {
            for (to in PrescriptionStatus.entries) {
                assertEquals((from to to) in allowed, from.canMoveTo(to), "$from -> $to")
            }
        }
    }

    @Then("it is final")
    fun isFinal() = assertTrue(current.status.isFinal, "${current.status} is final")

    @Then("it cannot move to any other status")
    fun cannotMove() = PrescriptionStatus.entries.forEach { next ->
        assertFalse(current.status.canMoveTo(next), "${current.status} -> $next")
    }

    /** The prescription as the last step left it. */
    private val current: Prescription
        get() {
            refusal?.let { throw AssertionError("The step was refused: ${it.message}", it) }
            return checkNotNull(prescription) { "No prescription in this scenario yet" }
        }

    private fun attempt(step: () -> Prescription) {
        try {
            prescription = step()
        } catch (e: DomainException) {
            refusal = e
        }
    }

    private inline fun <reified T : DomainException> expectRefusal(): T =
        assertIs<T>(refusal, "Expected the step to be refused").also { refusal = null }

    private fun checkIn(items: List<PrescriptionItem>) =
        Prescription.checkIn(PrescriptionId.random(), 7, patientName, items, now)

    private fun item(medication: String, packs: Int) =
        PrescriptionItem(MedicationId(medication.substringBefore(' ').lowercase()), medication, packs)

    /** Takes a new prescription to [status] the way it happens for real, one step at a time. */
    private fun reach(status: PrescriptionStatus): Prescription = when (status) {
        RECEIVED -> checkIn(listOf(item("Amoxicillin 500 mg", 2)))
        AWAITING_APPROVAL -> reach(RECEIVED).stockReserved(now)
        AWAITING_PACKAGING -> reach(AWAITING_APPROVAL).approve(now)
        READY_FOR_PICKUP -> reach(AWAITING_PACKAGING).markPacked(now)
        CALLED -> reach(READY_FOR_PICKUP).callPatient(now)
        COLLECTED -> reach(CALLED).handOver(now)
        OUT_OF_STOCK -> reach(RECEIVED).let { it.outOfStock(it.items, now) }
        REJECTED -> reach(AWAITING_APPROVAL).reject("Allergy on file", now)
        NO_SHOW -> reach(CALLED).noShow(now)
    }
}
