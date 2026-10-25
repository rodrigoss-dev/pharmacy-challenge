package pharmacy.infra.persistence

import jakarta.inject.Singleton
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import pharmacy.domain.PrescriptionStatusChanged
import pharmacy.domain.port.EventOutbox
import pharmacy.infra.messaging.StatusChangedMessages
import pharmacy.infra.metrics.BusinessMetrics
import pharmacy.infra.persistence.jooq.tables.references.OUTBOX
import java.time.Duration
import java.time.Instant

/**
 * The outbox table. Events are added in the transaction the caller opened around the use case, so each commits with the
 * state change it describes, or not at all (transactional outbox). The OutboxRelay drains it into RabbitMQ.
 */
@Singleton
class JooqEventOutbox(private val db: DSLContext, private val metrics: BusinessMetrics) : EventOutbox {

    data class Entry(val id: Long, val routingKey: String, val payload: String, val createdAt: Instant)

    /** What is waiting to be relayed: how many events, and since when. */
    data class Backlog(val events: Int, val oldest: Instant?) {
        fun oldestAge(now: Instant = Instant.now()): Duration? = oldest?.let { Duration.between(it, now) }
    }

    override fun add(event: PrescriptionStatusChanged) {
        db.insertInto(OUTBOX)
            .set(OUTBOX.ROUTING_KEY, StatusChangedMessages.routingKey(event.status))
            .set(OUTBOX.PAYLOAD, JSONB.valueOf(StatusChangedMessages.encode(event)))
            .execute()
        metrics.statusChanged(event.status)
    }

    /**
     * Events not yet confirmed by the broker, with the age of the oldest. Anything above zero for long means the broker is
     * unreachable; the age is the better alarm, since a busy minute also makes a long backlog.
     */
    fun backlogDetails(): Backlog =
        db.select(DSL.count(), DSL.min(OUTBOX.CREATED_AT)).from(OUTBOX).fetchSingle()
            .let { (count, oldest) -> Backlog(count, oldest?.toInstant()) }

    /**
     * The oldest events, locked until the transaction ends. Rows that another instance is relaying are skipped, so
     * several instances can relay at once without publishing the same event twice.
     */
    fun lockOldest(limit: Int): List<Entry> =
        db.selectFrom(OUTBOX)
            .orderBy(OUTBOX.ID)
            .limit(limit)
            .forUpdate()
            .skipLocked()
            .fetch { Entry(it.id!!, it.routingKey, it.payload.data(), it.createdAt!!.toInstant()) }

    fun remove(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        db.deleteFrom(OUTBOX).where(OUTBOX.ID.`in`(ids)).execute()
    }
}
