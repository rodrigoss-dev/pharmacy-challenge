package pharmacy.infra.persistence

import jakarta.inject.Singleton
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import pharmacy.domain.GovernmentCommit
import pharmacy.domain.PrescriptionId
import pharmacy.domain.port.GovernmentCommitOutbox
import pharmacy.infra.government.GovernmentMessages
import pharmacy.infra.persistence.jooq.tables.references.GOVERNMENT_COMMITS
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The outbox of commits for the government system. A commit is added in the transaction the caller opened around the
 * hand-over, so it commits with it, or not at all. GovernmentCommitRelay sends them, and records what came of it.
 */
@Singleton
class JooqGovernmentCommits(private val db: DSLContext) : GovernmentCommitOutbox {

    /** A commit taken for sending; [attempts] counts this one. */
    data class Entry(val commit: GovernmentCommit, val attempts: Int)

    /** What is waiting to go out, and what the government refused. */
    data class Backlog(val pending: Int, val oldestPending: Instant?, val rejected: Int) {
        fun oldestPendingAge(now: Instant = Instant.now()): Duration? = oldestPending?.let { Duration.between(it, now) }
    }

    override fun add(commit: GovernmentCommit) {
        val now = Instant.now().toOffsetDateTime()
        db.insertInto(GOVERNMENT_COMMITS)
            .set(GOVERNMENT_COMMITS.PRESCRIPTION_ID, commit.prescriptionId.value)
            .set(GOVERNMENT_COMMITS.GOVERNMENT_NUMBER, commit.governmentNumber)
            .set(GOVERNMENT_COMMITS.ITEMS, JSONB.valueOf(GovernmentMessages.encodeItems(commit.items)))
            .set(GOVERNMENT_COMMITS.STATUS, CommitStatus.PENDING)
            .set(GOVERNMENT_COMMITS.ATTEMPTS, 0)
            .set(GOVERNMENT_COMMITS.NEXT_ATTEMPT_AT, now)
            .set(GOVERNMENT_COMMITS.CREATED_AT, now)
            .execute()
    }

    /**
     * The oldest commits that are due at [now]. They are leased: not due again for [lease], so that the commit is not
     * sent twice at once by several instances, and is tried again if this instance dies before recording the outcome.
     * Rows another instance is claiming are skipped. Runs in a transaction of its own, which ends before anything is
     * sent: no database connection is held while the government answers.
     */
    fun claimDue(now: Instant, limit: Int, lease: Duration): List<Entry> {
        val due = db.selectFrom(GOVERNMENT_COMMITS)
            .where(GOVERNMENT_COMMITS.STATUS.eq(CommitStatus.PENDING))
            .and(GOVERNMENT_COMMITS.NEXT_ATTEMPT_AT.le(now.toOffsetDateTime()))
            .orderBy(GOVERNMENT_COMMITS.CREATED_AT)
            .limit(limit)
            .forUpdate()
            .skipLocked()
            .fetch()
        if (due.isEmpty()) return emptyList()
        db.update(GOVERNMENT_COMMITS)
            .set(GOVERNMENT_COMMITS.ATTEMPTS, GOVERNMENT_COMMITS.ATTEMPTS.plus(1))
            .set(GOVERNMENT_COMMITS.NEXT_ATTEMPT_AT, (now + lease).toOffsetDateTime())
            .where(GOVERNMENT_COMMITS.PRESCRIPTION_ID.`in`(due.map { it.prescriptionId }))
            .execute()
        return due.map {
            Entry(
                GovernmentCommit(
                    PrescriptionId(it.prescriptionId),
                    it.governmentNumber,
                    GovernmentMessages.decodeItems(it.items.data()),
                ),
                it.attempts + 1,
            )
        }
    }

    /** Gives back commits that were claimed but not sent: due at [now] again, and the attempt they never had is not counted. */
    fun release(ids: Collection<PrescriptionId>, now: Instant) {
        if (ids.isEmpty()) return
        db.update(GOVERNMENT_COMMITS)
            .set(GOVERNMENT_COMMITS.ATTEMPTS, GOVERNMENT_COMMITS.ATTEMPTS.minus(1))
            .set(GOVERNMENT_COMMITS.NEXT_ATTEMPT_AT, now.toOffsetDateTime())
            .where(GOVERNMENT_COMMITS.PRESCRIPTION_ID.`in`(ids.map { it.value }))
            .and(GOVERNMENT_COMMITS.STATUS.eq(CommitStatus.PENDING))
            .execute()
    }

    // The three outcomes below only apply to a commit that is still PENDING, and say whether they did. A relay that stalled
    // past its lease (a long pause, a frozen machine) may answer after another instance has sent the commit again and
    // recorded the outcome: the later answer must not undo it.

    /** False if the commit was not PENDING any more: someone else recorded an outcome already. */
    fun markCommitted(id: PrescriptionId, at: Instant): Boolean =
        db.update(GOVERNMENT_COMMITS)
            .set(GOVERNMENT_COMMITS.STATUS, CommitStatus.COMMITTED)
            .set(GOVERNMENT_COMMITS.SENT_AT, at.toOffsetDateTime())
            .setNull(GOVERNMENT_COMMITS.LAST_ERROR)
            .where(GOVERNMENT_COMMITS.PRESCRIPTION_ID.eq(id.value).and(GOVERNMENT_COMMITS.STATUS.eq(CommitStatus.PENDING)))
            .execute() == 1

    /** The government refused it for good. It stays in the table, out of the relay's reach, for someone to look at. */
    fun markRejected(id: PrescriptionId, error: String): Boolean =
        db.update(GOVERNMENT_COMMITS)
            .set(GOVERNMENT_COMMITS.STATUS, CommitStatus.REJECTED)
            .set(GOVERNMENT_COMMITS.LAST_ERROR, error)
            .where(GOVERNMENT_COMMITS.PRESCRIPTION_ID.eq(id.value).and(GOVERNMENT_COMMITS.STATUS.eq(CommitStatus.PENDING)))
            .execute() == 1

    fun scheduleRetry(id: PrescriptionId, at: Instant, error: String): Boolean =
        db.update(GOVERNMENT_COMMITS)
            .set(GOVERNMENT_COMMITS.NEXT_ATTEMPT_AT, at.toOffsetDateTime())
            .set(GOVERNMENT_COMMITS.LAST_ERROR, error)
            .where(GOVERNMENT_COMMITS.PRESCRIPTION_ID.eq(id.value).and(GOVERNMENT_COMMITS.STATUS.eq(CommitStatus.PENDING)))
            .execute() == 1

    fun backlog(): Backlog {
        val pending = db.select(DSL.count(), DSL.min(GOVERNMENT_COMMITS.CREATED_AT)).from(GOVERNMENT_COMMITS)
            .where(GOVERNMENT_COMMITS.STATUS.eq(CommitStatus.PENDING)).fetchSingle()
        val rejected = db.fetchCount(GOVERNMENT_COMMITS, GOVERNMENT_COMMITS.STATUS.eq(CommitStatus.REJECTED))
        return Backlog(pending.value1(), pending.value2()?.toInstant(), rejected)
    }

    private fun Instant.toOffsetDateTime(): OffsetDateTime = atOffset(ZoneOffset.UTC)
}

/**
 * The `status` column of government_commits: PENDING (to be sent, or sent and not acknowledged yet), COMMITTED, or
 * REJECTED (the government refused it for good).
 */
internal object CommitStatus {
    const val PENDING = "PENDING"
    const val COMMITTED = "COMMITTED"
    const val REJECTED = "REJECTED"
}
