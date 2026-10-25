package pharmacy.domain

import pharmacy.domain.PrescriptionStatus.NO_SHOW
import pharmacy.domain.PrescriptionStatus.OUT_OF_STOCK
import pharmacy.domain.PrescriptionStatus.REJECTED
import pharmacy.domain.port.EventOutbox
import pharmacy.domain.port.GovernmentCommitOutbox
import pharmacy.domain.port.GovernmentPrescriptions
import pharmacy.domain.port.InventoryRepository
import pharmacy.domain.port.PrescriptionRepository
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

// The ports of the domain, kept in memory: the use cases run in the scenarios without a database. They behave like the
// real ones where the rules depend on it (an update of an outdated version is refused, a ticket number is never reused).

class SettableClock(var at: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = at
}

class InMemoryPrescriptions : PrescriptionRepository {
    private val stored = linkedMapOf<PrescriptionId, Prescription>()
    private var lastTicket = 0

    /** Prescriptions whose commit the government system has accepted: it knows of them, so they hold nothing more. */
    val reported = mutableSetOf<PrescriptionId>()

    override fun add(prescription: Prescription) {
        stored[prescription.id] = prescription
    }

    override fun update(prescription: Prescription) {
        val current = stored[prescription.id]
        if (current == null || current.version != prescription.version) throw ConcurrentUpdateException(prescription.ticketNumber)
        stored[prescription.id] = prescription.copy(version = prescription.version + 1)
    }

    override fun findById(id: PrescriptionId): Prescription? = stored[id]

    override fun findByStatus(statuses: Collection<PrescriptionStatus>): List<Prescription> =
        stored.values.filter { it.status in statuses }.sortedBy { it.ticketNumber }

    override fun countInStatusSince(status: PrescriptionStatus, since: Instant): Int =
        stored.values.count { it.status == status && it.updatedAt <= since }

    override fun countAhead(prescription: Prescription): Int =
        stored.values.count { it.status == prescription.status && it.ticketNumber < prescription.ticketNumber }

    /** Runs once, right after the next ticket number is taken: what another request, queued ahead of this one, committed. */
    var whileQueued: (() -> Unit)? = null

    override fun nextTicketNumber(): Int {
        val number = ++lastTicket
        whileQueued?.let { other ->
            whileQueued = null
            other()
        }
        return number
    }

    override fun promisedOn(governmentNumber: String, since: Instant): Map<MedicationId, Int> =
        stored.values
            .filter { it.governmentNumber == governmentNumber && it.status !in WITHOUT_MEDICATION && it.id !in reported }
            .flatMap { it.items }
            .groupBy({ it.medicationId }, { it.quantity })
            .mapValues { (_, quantities) -> quantities.sum() }

    private companion object {
        val WITHOUT_MEDICATION = setOf(OUT_OF_STOCK, REJECTED, NO_SHOW)
    }
}

class InMemoryInventory(medications: List<Medication>) : InventoryRepository {
    private val stock = medications.associateByTo(linkedMapOf()) { it.id }

    fun set(medication: Medication) {
        stock[medication.id] = medication
    }

    fun packsOf(id: MedicationId): Int = stock.getValue(id).stock

    override fun findAll(): List<Medication> = stock.values.sortedBy { it.name }

    override fun findByIds(ids: Collection<MedicationId>): List<Medication> = ids.mapNotNull { stock[it] }.distinct()

    override fun tryReserve(id: MedicationId, quantity: Int): Boolean {
        val medication = stock[id] ?: return false
        if (medication.stock < quantity) return false
        stock[id] = medication.copy(stock = medication.stock - quantity)
        return true
    }

    override fun addStock(id: MedicationId, quantity: Int) {
        stock[id] = stock.getValue(id).let { it.copy(stock = it.stock + quantity) }
    }

    override fun lockForStockChecks() = Unit
}

class InMemoryEvents : EventOutbox {
    val recorded = mutableListOf<PrescriptionStatusChanged>()

    override fun add(event: PrescriptionStatusChanged) {
        recorded += event
    }
}

class InMemoryGovernmentCommits : GovernmentCommitOutbox {
    val queued = mutableListOf<GovernmentCommit>()

    override fun add(commit: GovernmentCommit) {
        queued += commit
    }
}

/** The government system: the prescriptions it knows, and whether it answers. */
class FakeGovernment : GovernmentPrescriptions {
    val known = mutableMapOf<String, GovernmentPrescription>()
    var down = false

    override fun find(number: String): GovernmentPrescription? {
        if (down) throw GovernmentSystemUnavailableException()
        return known[number.uppercase()]
    }
}
