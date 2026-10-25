package pharmacy.infra.persistence

import jakarta.inject.Singleton
import org.jooq.DSLContext
import pharmacy.domain.ConcurrentUpdateException
import pharmacy.domain.MedicationId
import pharmacy.domain.Prescription
import pharmacy.domain.PrescriptionId
import pharmacy.domain.PrescriptionItem
import pharmacy.domain.PrescriptionStatus
import pharmacy.domain.port.PrescriptionRepository
import org.jooq.impl.DSL
import pharmacy.infra.persistence.jooq.tables.records.PrescriptionItemsRecord
import pharmacy.infra.persistence.jooq.tables.records.PrescriptionsRecord
import pharmacy.infra.persistence.jooq.tables.references.GOVERNMENT_COMMITS
import pharmacy.infra.persistence.jooq.tables.references.PRESCRIPTIONS
import pharmacy.infra.persistence.jooq.tables.references.PRESCRIPTION_ITEMS
import pharmacy.infra.persistence.jooq.tables.references.TICKET_COUNTER
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

// Records are mapped to domain objects by hand, so the domain model stays free of persistence concerns.
@Singleton
class JooqPrescriptionRepository(private val db: DSLContext) : PrescriptionRepository {

    override fun add(prescription: Prescription) {
        db.executeInsert(
            PrescriptionsRecord(
                id = prescription.id.value,
                ticketNumber = prescription.ticketNumber,
                patientName = prescription.patientName,
                status = prescription.status.name,
                statusReason = prescription.statusReason,
                createdAt = prescription.createdAt.toOffsetDateTime(),
                updatedAt = prescription.updatedAt.toOffsetDateTime(),
                version = prescription.version,
                governmentNumber = prescription.governmentNumber,
            ),
        )
        db.batchInsert(
            prescription.items.map {
                PrescriptionItemsRecord(prescription.id.value, it.medicationId.value, it.medicationName, it.quantity)
            },
        ).execute()
    }

    /**
     * Only the status fields change after check-in (the government number is set once, by [add]). The version check
     * makes concurrent updates fail loudly.
     */
    override fun update(prescription: Prescription) {
        val updatedRows = db.update(PRESCRIPTIONS)
            .set(PRESCRIPTIONS.STATUS, prescription.status.name)
            .set(PRESCRIPTIONS.STATUS_REASON, prescription.statusReason)
            .set(PRESCRIPTIONS.UPDATED_AT, prescription.updatedAt.toOffsetDateTime())
            .set(PRESCRIPTIONS.VERSION, prescription.version + 1)
            .where(PRESCRIPTIONS.ID.eq(prescription.id.value).and(PRESCRIPTIONS.VERSION.eq(prescription.version)))
            .execute()
        if (updatedRows == 0) throw ConcurrentUpdateException(prescription.ticketNumber)
    }

    override fun findById(id: PrescriptionId): Prescription? =
        db.selectFrom(PRESCRIPTIONS)
            .where(PRESCRIPTIONS.ID.eq(id.value))
            .fetch()
            .withItems()
            .singleOrNull()

    override fun findByStatus(statuses: Collection<PrescriptionStatus>): List<Prescription> {
        if (statuses.isEmpty()) return emptyList()
        return db.selectFrom(PRESCRIPTIONS)
            .where(PRESCRIPTIONS.STATUS.`in`(statuses.map { it.name }))
            .orderBy(PRESCRIPTIONS.TICKET_NUMBER)
            .fetch()
            .withItems()
    }

    override fun countInStatusSince(status: PrescriptionStatus, since: Instant): Int =
        db.fetchCount(
            PRESCRIPTIONS,
            PRESCRIPTIONS.STATUS.eq(status.name).and(PRESCRIPTIONS.UPDATED_AT.le(since.toOffsetDateTime())),
        )

    override fun countAhead(prescription: Prescription): Int =
        db.fetchCount(
            PRESCRIPTIONS,
            PRESCRIPTIONS.STATUS.eq(prescription.status.name)
                .and(PRESCRIPTIONS.TICKET_NUMBER.lt(prescription.ticketNumber)),
        )

    /** Locks the counter row until the check-in commits or rolls back. */
    override fun nextTicketNumber(): Int =
        db.update(TICKET_COUNTER)
            .set(TICKET_COUNTER.LAST_NUMBER, TICKET_COUNTER.LAST_NUMBER.plus(1))
            .returningResult(TICKET_COUNTER.LAST_NUMBER)
            .fetchSingle()
            .value1()!!

    /**
     * What tickets on a government prescription hold that the government system does not know of. A ticket holds its
     * packs unless it ended without them. One that is collected is the government's to count once it accepted the commit,
     * unless that was after it was asked ([since]): then its figures do not show it yet. A commit it refused for good is
     * not counted: the figures are the government's truth.
     */
    override fun promisedOn(governmentNumber: String, since: Instant): Map<MedicationId, Int> =
        db.select(PRESCRIPTION_ITEMS.MEDICATION_ID, DSL.sum(PRESCRIPTION_ITEMS.QUANTITY))
            .from(PRESCRIPTIONS)
            .join(PRESCRIPTION_ITEMS).on(PRESCRIPTION_ITEMS.PRESCRIPTION_ID.eq(PRESCRIPTIONS.ID))
            .leftJoin(GOVERNMENT_COMMITS).on(GOVERNMENT_COMMITS.PRESCRIPTION_ID.eq(PRESCRIPTIONS.ID))
            .where(PRESCRIPTIONS.GOVERNMENT_NUMBER.eq(governmentNumber))
            .and(PRESCRIPTIONS.STATUS.notIn(WITHOUT_MEDICATION.map { it.name }))
            .and(
                GOVERNMENT_COMMITS.PRESCRIPTION_ID.isNull
                    .or(GOVERNMENT_COMMITS.STATUS.eq(CommitStatus.PENDING))
                    .or(GOVERNMENT_COMMITS.STATUS.eq(CommitStatus.COMMITTED).and(GOVERNMENT_COMMITS.SENT_AT.ge(since.toOffsetDateTime()))),
            )
            .groupBy(PRESCRIPTION_ITEMS.MEDICATION_ID)
            .fetch()
            .associate { (medication, packs) -> MedicationId(medication!!) to packs!!.toInt() }

    /** Maps the prescription records, then loads the items of all of them with a single query. */
    private fun List<PrescriptionsRecord>.withItems(): List<Prescription> {
        if (isEmpty()) return emptyList()
        val itemsByPrescription = db.selectFrom(PRESCRIPTION_ITEMS)
            .where(PRESCRIPTION_ITEMS.PRESCRIPTION_ID.`in`(map { it.id }))
            .orderBy(PRESCRIPTION_ITEMS.MEDICATION_NAME)
            .fetchGroups(PRESCRIPTION_ITEMS.PRESCRIPTION_ID) {
                PrescriptionItem(MedicationId(it.medicationId), it.medicationName, it.quantity)
            }
        return map { it.toPrescription(itemsByPrescription[it.id].orEmpty()) }
    }

    private fun PrescriptionsRecord.toPrescription(items: List<PrescriptionItem>) = Prescription(
        id = PrescriptionId(id),
        ticketNumber = ticketNumber,
        patientName = patientName,
        items = items,
        status = PrescriptionStatus.valueOf(status),
        statusReason = statusReason,
        createdAt = createdAt.toInstant(),
        updatedAt = updatedAt.toInstant(),
        version = version,
        governmentNumber = governmentNumber,
    )

    private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)

    private companion object {
        /** Ended without the medication leaving the shelf. */
        val WITHOUT_MEDICATION = listOf(PrescriptionStatus.OUT_OF_STOCK, PrescriptionStatus.REJECTED, PrescriptionStatus.NO_SHOW)
    }
}
