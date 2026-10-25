package pharmacy.infra.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.MultiGauge
import io.micrometer.core.instrument.Tags
import jakarta.inject.Singleton
import jakarta.transaction.Status
import jakarta.transaction.Synchronization
import jakarta.transaction.TransactionSynchronizationRegistry
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import pharmacy.domain.PrescriptionStatus
import pharmacy.infra.persistence.DatabaseUnavailableException
import pharmacy.infra.persistence.Transactions
import pharmacy.infra.persistence.jooq.tables.references.MEDICATIONS
import pharmacy.infra.persistence.jooq.tables.references.PRESCRIPTIONS
import java.time.Duration
import java.time.OffsetDateTime

/**
 * What the pharmacy is doing, as Prometheus metrics (the business side of `/q/metrics`; the business side of the logs
 * is `log_type=business`).
 *
 * - `pharmacy_prescription_status_changes_total{status}`: every prescription that moved into a status, check-ins
 *   included (`RECEIVED`). Counted when the change commits, so a rolled-back attempt or a retry is not counted twice.
 * - `pharmacy_prescriptions{status}`, `pharmacy_prescription_oldest_wait_seconds{status}`,
 *   `pharmacy_medication_stock{medication}`, `pharmacy_turnaround_seconds`: read from the database by [refresh], not
 *   on every scrape. While the database cannot be read they have no value, rather than a stale one.
 */
@Singleton
class BusinessMetrics(
    private val registry: MeterRegistry,
    private val db: DSLContext,
    private val transactions: Transactions,
    private val transactionRegistry: TransactionSynchronizationRegistry,
) {
    private data class Snapshot(
        val prescriptions: Map<PrescriptionStatus, Int>,
        /** Since each prescription entered its status; only for statuses someone still has to act on. */
        val oldestWait: Map<PrescriptionStatus, Duration>,
        val turnaround: Duration?,
        val stock: Map<String, Int>,
    )

    private val statusChanges = PrescriptionStatus.entries.associateWith {
        Counter.builder("pharmacy.prescription.status.changes")
            .description("Prescriptions that moved into each status, check-ins included")
            .tag("status", it.name)
            .register(registry)
    }
    private val stock = MultiGauge.builder("pharmacy.medication.stock")
        .description("Packs in stock, per medication")
        .register(registry)

    @Volatile
    private var snapshot: Snapshot? = null

    init {
        PrescriptionStatus.entries.forEach { status ->
            Gauge.builder("pharmacy.prescriptions") { snapshot?.let { (it.prescriptions[status] ?: 0).toDouble() } ?: Double.NaN }
                .description("Prescriptions in each status right now")
                .tag("status", status.name)
                .register(registry)
            if (!status.isFinal) {
                Gauge.builder("pharmacy.prescription.oldest.wait") { snapshot?.let { it.oldestWait[status]?.toMillis()?.div(1000.0) ?: 0.0 } ?: Double.NaN }
                    .description("How long the longest-waiting prescription has been in this status")
                    .baseUnit("seconds")
                    .tag("status", status.name)
                    .register(registry)
            }
        }
        Gauge.builder("pharmacy.turnaround") { snapshot?.turnaround?.toMillis()?.div(1000.0) ?: Double.NaN }
            .description("Average time from check-in to hand-over, for prescriptions collected in the last hour")
            .baseUnit("seconds")
            .register(registry)
    }

    /** Called by the outbox for every status change, inside the transaction that makes it. */
    fun statusChanged(status: PrescriptionStatus) {
        val counter = statusChanges.getValue(status)
        if (transactionRegistry.transactionStatus == Status.STATUS_ACTIVE) {
            transactionRegistry.registerInterposedSynchronization(object : Synchronization {
                override fun beforeCompletion() = Unit

                override fun afterCompletion(outcome: Int) {
                    if (outcome == Status.STATUS_COMMITTED) counter.increment()
                }
            })
        } else {
            counter.increment()
        }
    }

    /** Reads the database and updates the gauges. An unexpected failure clears them and is thrown on. */
    fun refresh() {
        var loaded: Snapshot? = null
        try {
            loaded = transactions.read("metrics: prescriptions and stock") { load() }
        } catch (_: DatabaseUnavailableException) {
            // DatabaseHealth reports the outage; the gauges have no value meanwhile.
        } finally {
            snapshot = loaded
            stock.register(loaded?.stock?.map { (medication, packs) -> MultiGauge.Row.of(Tags.of("medication", medication), packs) }.orEmpty(), true)
        }
    }

    private fun load(): Snapshot {
        val now = OffsetDateTime.now()
        val byStatus = db.select(PRESCRIPTIONS.STATUS, DSL.count(), DSL.min(PRESCRIPTIONS.UPDATED_AT))
            .from(PRESCRIPTIONS)
            .groupBy(PRESCRIPTIONS.STATUS)
            .fetch()
            .mapNotNull { (status, count, oldest) ->
                PrescriptionStatus.entries.find { it.name == status }?.let { Triple(it, count, oldest) }
            }
        val turnaroundSeconds = db.select(
            DSL.avg(DSL.field("extract(epoch from ({0} - {1}))", SQLDataType.DOUBLE, PRESCRIPTIONS.UPDATED_AT, PRESCRIPTIONS.CREATED_AT)),
        )
            .from(PRESCRIPTIONS)
            .where(PRESCRIPTIONS.STATUS.eq(PrescriptionStatus.COLLECTED.name).and(PRESCRIPTIONS.UPDATED_AT.gt(now.minusHours(1))))
            .fetchSingle()
            .value1()
        return Snapshot(
            prescriptions = byStatus.associate { (status, count, _) -> status to count },
            oldestWait = byStatus
                .filter { (status, _, oldest) -> !status.isFinal && oldest != null }
                .associate { (status, _, oldest) -> status to Duration.between(oldest, now) },
            turnaround = turnaroundSeconds?.let { Duration.ofMillis((it.toDouble() * 1000).toLong()) },
            stock = db.select(MEDICATIONS.NAME, MEDICATIONS.STOCK).from(MEDICATIONS).fetch().associate { (name, packs) -> name!! to packs!! },
        )
    }
}
