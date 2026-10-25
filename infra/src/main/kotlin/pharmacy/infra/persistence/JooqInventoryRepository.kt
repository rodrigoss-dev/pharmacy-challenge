package pharmacy.infra.persistence

import jakarta.inject.Singleton
import org.jooq.DSLContext
import pharmacy.domain.Medication
import pharmacy.domain.MedicationId
import pharmacy.domain.port.InventoryRepository
import pharmacy.infra.persistence.jooq.tables.records.MedicationsRecord
import pharmacy.infra.persistence.jooq.tables.references.MEDICATIONS

@Singleton
class JooqInventoryRepository(private val db: DSLContext) : InventoryRepository {

    override fun findAll(): List<Medication> =
        db.selectFrom(MEDICATIONS)
            .orderBy(MEDICATIONS.NAME)
            .fetch(::toMedication)

    override fun findByIds(ids: Collection<MedicationId>): List<Medication> {
        if (ids.isEmpty()) return emptyList()
        return db.selectFrom(MEDICATIONS)
            .where(MEDICATIONS.ID.`in`(ids.map { it.value }))
            .fetch(::toMedication)
    }

    /** A conditional decrement, so two concurrent reservations can never take the stock below zero. */
    override fun tryReserve(id: MedicationId, quantity: Int): Boolean =
        db.update(MEDICATIONS)
            .set(MEDICATIONS.STOCK, MEDICATIONS.STOCK.minus(quantity))
            .where(MEDICATIONS.ID.eq(id.value).and(MEDICATIONS.STOCK.ge(quantity)))
            .execute() == 1

    /** An advisory lock, released when the transaction commits or rolls back: nothing to clean up if an instance dies. */
    override fun lockForStockChecks() {
        db.fetch("select pg_advisory_xact_lock(?)", STOCK_CHECK_LOCK)
    }

    override fun addStock(id: MedicationId, quantity: Int) {
        db.update(MEDICATIONS)
            .set(MEDICATIONS.STOCK, MEDICATIONS.STOCK.plus(quantity))
            .where(MEDICATIONS.ID.eq(id.value))
            .execute()
    }

    private fun toMedication(record: MedicationsRecord) = Medication(
        id = MedicationId(record.id),
        name = record.name,
        category = record.category,
        stock = record.stock,
    )

    private companion object {
        /** Any number will do, as long as nothing else in this database takes an advisory lock with it. */
        const val STOCK_CHECK_LOCK = 4_217_001L
    }
}
