package pharmacy.domain

import io.cucumber.java.ParameterType
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import pharmacy.domain.usecase.ListWorkQueues
import pharmacy.domain.usecase.LookUpGovernmentPrescription
import pharmacy.domain.usecase.MedicationCatalog
import pharmacy.domain.usecase.PrescriptionUpdater
import pharmacy.domain.usecase.ReserveStock
import pharmacy.domain.usecase.StaffActions
import pharmacy.domain.usecase.SubmitPrescription
import pharmacy.domain.usecase.TrackPrescription
import pharmacy.domain.usecase.WaitingRoomBoard
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Steps for use-cases.feature: the use cases themselves, wired to the ports in memory (see InMemoryPorts.kt). Patients
 * are named, so a scenario can say who checked in first. Cucumber creates a new instance per scenario.
 */
class UseCaseSteps {
    private val clock = SettableClock(Instant.parse("2026-10-23T10:00:00Z"))
    private val prescriptions = InMemoryPrescriptions()
    private val inventory = InMemoryInventory(
        listOf("Amoxicillin 500 mg", "Atorvastatin 20 mg", "Ibuprofen 400 mg", "Metformin 850 mg")
            .map { Medication(id(it), it, "Any", stock = 10) },
    )
    private val events = InMemoryEvents()
    private val commits = InMemoryGovernmentCommits()
    private val updater = PrescriptionUpdater(prescriptions, events)
    private val reserveStock = ReserveStock(updater, prescriptions, inventory, clock)
    private val staff = StaffActions(updater, inventory, clock, reserveStock, commits)
    private val submit = SubmitPrescription(prescriptions, inventory, events, clock)
    private val track = TrackPrescription(prescriptions, clock)
    private val board = WaitingRoomBoard(prescriptions)
    private val queues = ListWorkQueues(prescriptions)
    private val catalog = MedicationCatalog(inventory)
    private val government = FakeGovernment()
    private val lookUp = LookUpGovernmentPrescription(government)

    private val tickets = mutableMapOf<String, Prescription>()
    private val commands = mutableMapOf<String, SubmitPrescription.Command>()
    private val ids = mutableMapOf<String, PrescriptionId>()
    private var governmentPrescription: GovernmentPrescription? = null
    private var looked: GovernmentPrescription? = null
    private var refusal: DomainException? = null
    private var raced: CheckInRacedException? = null

    @ParameterType("[A-Z][a-z]+")
    fun person(name: String) = name

    // The pharmacy

    @Given("the pharmacy has {int} packs of {medication}")
    fun stockOf(packs: Int, medication: String) = inventory.set(Medication(id(medication), medication, "Any", packs))

    @Then("the shelf has {int} packs of {medication}")
    fun shelfHas(packs: Int, medication: String) = assertEquals(packs, inventory.packsOf(id(medication)))

    // Checking in

    @Given("{person} checked in for {int} packs of {medication}")
    fun checkedIn(name: String, packs: Int, medication: String) = checkedInFor(name, listOf(PrescriptionItem(id(medication), medication, packs)))

    @Given("{person} checked in for:")
    fun checkedInFor(name: String, items: List<PrescriptionItem>) {
        val command = SubmitPrescription.Command(null, name, items.map { SubmitPrescription.Command.Item(it.medicationId, it.quantity) })
        tickets[name] = submit(command)
        commands[name] = command
    }

    /** A patient who was turned away asks again: a new ticket, which replaces the old one in this scenario. */
    @When("{person} checks in again for {int} packs of {medication}")
    fun checksInAgain(name: String, packs: Int, medication: String) = checkedIn(name, packs, medication)

    @Given("{person} checked in with the id {word} for {int} packs of {medication}")
    fun checkedInWithId(name: String, id: String, packs: Int, medication: String) {
        val command = commandWithId(name, id, packs, medication)
        tickets[name] = submit(command)
        commands[name] = command
    }

    @When("{person} checks in with the id {word} for {int} packs of {medication}")
    fun checksInWithId(name: String, id: String, packs: Int, medication: String) = attempt {
        tickets[name] = submit(commandWithId(name, id, packs, medication))
    }

    @When("{person} sends the same check-in again")
    fun sendsAgain(name: String) = attempt { tickets["$name again"] = submit(commands.getValue(name)) }

    @Then("{person} gets the same ticket as before")
    fun sameTicket(name: String) = assertEquals(tickets.getValue(name), tickets.getValue("$name again"))

    @Then("only one check-in was recorded")
    fun oneCheckIn() = assertEquals(1, events.recorded.count { it.status == PrescriptionStatus.RECEIVED })

    @Then("{person} is told {string}")
    fun isTold(name: String, message: String) = assertEquals(message, checkNotNull(refusal) { "$name was not refused" }.message)

    @Then("{person}'s ticket number is {int}")
    fun ticketNumber(name: String, number: Int) = assertEquals(number, tickets.getValue(name).ticketNumber)

    // Stock checks

    @When("{person}'s stock check runs")
    fun stockCheckRuns(name: String) = attempt { reserveStock(tickets.getValue(name).id) }

    @Given("{person}'s stock check ran")
    fun stockCheckRan(name: String) = stockCheckRuns(name)

    @When("a repeated message makes {person}'s stock check run again")
    fun stockCheckRunsAgain(name: String) = stockCheckRuns(name)

    @When("staff retry {person}'s stock check")
    fun retryStockCheck(name: String) = attempt { staff.retryStockCheck(tickets.getValue(name).id) }

    @Then("{person}'s prescription is {status}")
    fun hasStatus(name: String, status: PrescriptionStatus) = assertEquals(status, current(name).status)

    // Staff

    @Given("{person}'s prescription was approved, packed and called")
    fun approvedPackedCalled(name: String) {
        val id = tickets.getValue(name).id
        staff.approve(id)
        staff.markPacked(id)
        staff.callPatient(id)
    }

    @Given("{person}'s prescription was approved and packed")
    fun approvedAndPacked(name: String) {
        val id = tickets.getValue(name).id
        staff.approve(id)
        staff.markPacked(id)
    }

    @When("the pharmacist rejects {person}'s prescription with the reason {string}")
    fun reject(name: String, reason: String) = attempt { staff.reject(tickets.getValue(name).id, reason) }

    @When("{person} does not answer when called")
    fun noShow(name: String) = attempt { staff.noShow(tickets.getValue(name).id) }

    @When("{person}'s prescription is handed over")
    fun handOver(name: String) = attempt { staff.handOver(tickets.getValue(name).id) }

    @Then("the staff member is turned away with {string}")
    fun staffTurnedAway(message: String) = assertEquals(message, assertIs<IllegalTransitionException>(refusal).message)

    // The patient's page, the board and the queues

    @When("{int} seconds pass")
    fun secondsPass(seconds: Int) {
        clock.at = clock.at.plus(Duration.ofSeconds(seconds.toLong()))
    }

    @Then("{person}'s page says the stock check is taking longer than usual")
    fun pageSaysDelayed(name: String) = assertTrue(track(tickets.getValue(name).id).delayed, "delayed")

    @Then("{person}'s page does not say the stock check is taking longer than usual")
    fun pageDoesNotSayDelayed(name: String) = assertEquals(false, track(tickets.getValue(name).id).delayed)

    @Then("the board shows {string} being called, {string} ready and {string} being prepared")
    fun boardShows(calling: String, ready: String, preparing: String) {
        val shown = board()
        assertEquals(numbers(calling), shown.nowCalling)
        assertEquals(numbers(ready), shown.ready)
        assertEquals(numbers(preparing), shown.inProgress)
    }

    @Then("the pharmacist queue lists {string}")
    fun pharmacistQueue(names: String) = assertEquals(names, queues().pharmacist.joinToString(", ") { it.patientName })

    @Then("the pickup queue lists {string}")
    fun pickupQueue(names: String) = assertEquals(names, queues().pickup.joinToString(", ") { it.patientName })

    // Medication

    @When("the stock of {medication} is topped up by {int} packs")
    fun restock(medication: String, packs: Int) = attempt { catalog.restock(id(medication), packs) }

    @When("the stock of {word} is topped up by {int} packs")
    fun restockUnknown(medication: String, packs: Int) = attempt { catalog.restock(MedicationId(medication), packs) }

    @Then("the restock is refused with {string}")
    fun restockRefused(message: String) = assertEquals(message, checkNotNull(refusal) { "The restock was accepted" }.message)

    // Government prescriptions

    @Given("the government prescription {word} lists {int} packs of {medication} as a maximum")
    fun governmentLists(number: String, packs: Int, medication: String) {
        governmentPrescription = GovernmentPrescription(number, listOf(GovernmentPrescription.Item(id(medication), packs, maximum = true)), clock.at)
    }

    @When("{person} checks in on it for {int} packs of {medication}")
    fun checksInOnIt(name: String, packs: Int, medication: String) = attempt {
        val items = listOf(SubmitPrescription.Command.Item(id(medication), packs))
        val command = SubmitPrescription.Command(null, name, items, governmentPrescription)
        tickets[name] = submit(command)
        commands[name] = command
    }

    @Given("{person} checked in on it for {int} packs of {medication}")
    fun checkedInOnIt(name: String, packs: Int, medication: String) {
        checksInOnIt(name, packs, medication)
        refusal?.let { throw AssertionError("The check-in was refused: ${it.message}", it) }
    }

    @When("{person} checks in on it with the id {word} for {int} packs of {medication}, and a double tap sends the same check-in while it waits for its ticket number")
    fun checksInOnItDoubleTapped(name: String, id: String, packs: Int, medication: String) {
        val items = listOf(SubmitPrescription.Command.Item(id(medication), packs))
        val command = SubmitPrescription.Command(ids.getOrPut(id) { PrescriptionId.random() }, name, items, governmentPrescription)
        prescriptions.whileQueued = { tickets[name] = submit(command) }
        commands[name] = command
        refusal = null
        raced = try {
            submit(command)
            null
        } catch (e: CheckInRacedException) {
            e
        }
    }

    @Then("{person}'s check-in is thrown back to be run again, not refused")
    fun thrownBack(name: String) {
        assertNull(refusal, "$name was refused")
        assertTrue(raced != null, "$name's check-in went through twice")
    }

    @Given("the government system has been told what {person} took")
    fun governmentToldWhatTook(name: String) {
        val prescription = tickets.getValue(name)
        val listed = checkNotNull(governmentPrescription)
        governmentPrescription = listed.copy(
            items = listed.items.map { item ->
                val taken = prescription.items.firstOrNull { it.medicationId == item.medicationId }?.quantity ?: 0
                item.copy(quantity = item.quantity - taken)
            },
        )
        prescriptions.reported += prescription.id
    }

    @Then("{int} commit(s) to the government system is/are queued")
    fun commitsQueued(count: Int) = assertEquals(count, commits.queued.size)

    @Given("the government system knows GOV-1001 with {int} packs of {medication} left, and GOV-1004 used in full")
    fun governmentKnows(packs: Int, medication: String) {
        government.known["GOV-1001"] = GovernmentPrescription("GOV-1001", listOf(GovernmentPrescription.Item(id(medication), packs, true)), clock.at)
        government.known["GOV-1004"] = GovernmentPrescription("GOV-1004", listOf(GovernmentPrescription.Item(id(medication), 0, false)), clock.at)
    }

    @Given("the government system does not answer")
    fun governmentDown() {
        government.down = true
    }

    @When("a patient looks up {string}")
    fun looksUp(typed: String) = attempt { looked = lookUp(typed) }

    @Then("they see government prescription {word} with {int} packs of {medication} to collect")
    fun sees(number: String, packs: Int, medication: String) {
        val found = checkNotNull(looked) { "Nothing was found: ${refusal?.message}" }
        assertEquals(number, found.number)
        assertEquals(listOf(id(medication) to packs), found.items.map { it.medicationId to it.quantity })
    }

    @Then("the patient is told {string}")
    fun patientIsTold(message: String) = assertEquals(message, checkNotNull(refusal) { "The look-up worked" }.message)

    @Then("the look-up fails: {word}")
    fun lookUpFails(type: String) {
        assertNull(looked)
        assertEquals(type, checkNotNull(refusal)::class.simpleName)
    }

    // Helpers

    private fun commandWithId(name: String, id: String, packs: Int, medication: String): SubmitPrescription.Command {
        val prescriptionId = ids.getOrPut(id) { PrescriptionId.random() }
        return SubmitPrescription.Command(prescriptionId, name, listOf(SubmitPrescription.Command.Item(id(medication), packs)))
    }

    private fun current(name: String): Prescription = checkNotNull(prescriptions.findById(tickets.getValue(name).id))

    private fun attempt(step: () -> Unit) {
        refusal = null
        try {
            step()
        } catch (e: DomainException) {
            refusal = e
        }
    }

    private fun numbers(list: String): List<Int> = list.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { it.toInt() }

    private fun id(medication: String) = MedicationId(medication.substringBefore(' ').lowercase())
}
