package pharmacy.infra.government

import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.event.Observes
import jakarta.inject.Singleton
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import pharmacy.domain.GovernmentCommit
import pharmacy.infra.persistence.DatabaseHealth
import pharmacy.infra.persistence.DatabaseUnavailableException
import pharmacy.infra.persistence.JooqGovernmentCommits
import pharmacy.infra.persistence.Transactions
import pharmacy.infra.persistence.pretty
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Sends the commits in the outbox to the government system, oldest first. The commit was written with the hand-over,
 * so it survives a government outage and a restart of this app; this is what finally delivers it.
 *
 * - Accepted: the commit is marked as done, and kept.
 * - Unreachable, slow or failing: it stays in the outbox and is tried again with growing pauses, up to [MAX_BACKOFF].
 *   The patient has long gone home with their medication; nothing waits on this.
 * - Refused for good (the government said no): marked REJECTED and logged as an error. Trying again cannot help, so
 *   someone has to look; the staff console says so.
 *
 * The government may receive a commit more than once (this app dies after sending it, before noting that it was
 * accepted), which is why the prescription id travels with it: the government recognizes a repeat and changes nothing.
 * Safe on several instances at once. No database connection is held while the government is being called.
 */
@Singleton
class GovernmentCommitRelay(
    @ConfigProperty(name = "pharmacy.government-commits.relay.enabled") private val enabled: Boolean,
    @ConfigProperty(name = "pharmacy.government-commits.relay.every") private val every: Duration,
    private val commits: JooqGovernmentCommits,
    private val government: GovernmentApiClient,
    private val transactions: Transactions,
    private val database: DatabaseHealth,
) {
    private val log = Logger.getLogger(javaClass)
    private val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "government-commit-relay").apply { isDaemon = true } }

    /** When the relay last went round; null if it is disabled. */
    @Volatile
    var lastRunAt: Instant? = null
        private set

    fun start(@Observes event: StartupEvent) {
        if (!enabled) {
            log.info("Government commit relay disabled (pharmacy.government-commits.relay.enabled=false): commits stay in the outbox")
            return
        }
        lastRunAt = Instant.now()
        log.infof("Government commit relay: every %s", every.pretty())
        scheduler.scheduleWithFixedDelay(::relaySafely, every.toMillis(), every.toMillis(), TimeUnit.MILLISECONDS)
    }

    fun stop(@Observes event: ShutdownEvent) {
        scheduler.shutdownNow()
    }

    /** How often the relay goes round. */
    val interval: Duration get() = every

    /**
     * Runs one round now: sends what is due at [now]. Returns how many commits the government accepted.
     *
     * A round ends early, handing back what it has not sent, when the government fails (calling it again for every
     * commit in the batch would only wait out one timeout after another) or when it has taken half the lease: the
     * commits claimed must not become due for another instance while this one is still on its way to them.
     */
    fun relay(now: Instant = Instant.now()): Int {
        lastRunAt = Instant.now()
        if (!database.checkIfDue()) return 0 // DatabaseHealth logs the outage

        val due = try {
            transactions.transaction("claiming government commits") { commits.claimDue(now, BATCH_SIZE, LEASE) }
        } catch (_: DatabaseUnavailableException) {
            return 0
        }
        val started = System.nanoTime()
        var accepted = 0
        for ((position, entry) in due.withIndex()) {
            val (commit, attempts) = entry
            val ticket = "prescription ${commit.prescriptionId}"
            // Outside any transaction: the government may take its whole timeout to answer.
            val outcome = government.commit(commit)
            // A retry is due a pause after the answer, not after the start of the round: the call may have used its timeout.
            val elapsed = Duration.ofNanos(System.nanoTime() - started)
            try {
                transactions.transaction("recording the outcome of the government commit for ${commit.prescriptionId}") {
                    record(commit, attempts, outcome, now + elapsed)
                }
            } catch (_: DatabaseUnavailableException) {
                // Sent but not recorded: it is leased for a minute, then sent again, which the government tolerates.
                log.warnf("Sent the government commit for %s, but the database is unavailable to record it; it will be sent again", ticket)
                break
            }
            if (outcome is CommitOutcome.Accepted) accepted++
            if (outcome is CommitOutcome.Failed || elapsed >= ROUND_BUDGET) {
                giveBack(due.drop(position + 1), now)
                break
            }
        }
        return accepted
    }

    /** What a round left unsent is due again at once, instead of when its lease runs out. */
    private fun giveBack(unsent: List<JooqGovernmentCommits.Entry>, now: Instant) {
        if (unsent.isEmpty()) return
        try {
            transactions.transaction("giving back ${unsent.size} government commit(s)") {
                commits.release(unsent.map { it.commit.prescriptionId }, now)
            }
        } catch (_: DatabaseUnavailableException) {
            // They are sent when their lease runs out.
        }
    }

    private fun record(commit: GovernmentCommit, attempts: Int, outcome: CommitOutcome, now: Instant) {
        val used = commit.items.joinToString { "${it.quantity}x ${it.medicationId}" }
        val recorded = when (outcome) {
            CommitOutcome.Accepted -> commits.markCommitted(commit.prescriptionId, Instant.now())
            is CommitOutcome.Rejected -> commits.markRejected(commit.prescriptionId, outcome.reason)
            is CommitOutcome.Failed -> commits.scheduleRetry(commit.prescriptionId, now + retryDelay(attempts), outcome.reason)
        }
        if (!recorded) {
            // This instance stalled past its lease; another one sent the commit again and recorded how it went.
            log.warnf(
                "The answer to the government commit for prescription %s (%s) came too late: its outcome was recorded already, so this one is left out",
                commit.prescriptionId, outcome.javaClass.simpleName,
            )
            return
        }
        when (outcome) {
            CommitOutcome.Accepted -> {
                log.infof(
                    "Committed %s to government prescription %s%s",
                    used, commit.governmentNumber, if (attempts > 1) " (attempt $attempts)" else "",
                )
            }
            is CommitOutcome.Rejected -> {
                log.errorf(
                    "The government refused the commit of %s to prescription %s (%s), for the pharmacy prescription %s. " +
                        "It will not be sent again: someone has to sort it out with the government",
                    used, commit.governmentNumber, outcome.reason, commit.prescriptionId,
                )
            }
            is CommitOutcome.Failed -> {
                log.warnf(
                    "Could not commit %s to government prescription %s (attempt %d: %s). It stays in the outbox; next try in %s",
                    used, commit.governmentNumber, attempts, outcome.reason, retryDelay(attempts).pretty(),
                )
            }
        }
    }

    private fun relaySafely() {
        // An exception escaping a scheduled task would cancel every later run.
        try {
            relay()
        } catch (e: Throwable) {
            log.errorf(e, "Government commit relay: unexpected failure; trying again in %s", every.pretty())
        }
    }

    /** 5s, 10s, 20s... up to [MAX_BACKOFF]. */
    private fun retryDelay(attempts: Int): Duration =
        minOf(FIRST_RETRY.multipliedBy(1L shl minOf(attempts - 1, 10)), MAX_BACKOFF)

    private companion object {
        const val BATCH_SIZE = 20

        /** Longer than a round can take: [ROUND_BUDGET], plus the last call (its timeout) and recording its outcome. */
        val LEASE: Duration = Duration.ofMinutes(1)
        val ROUND_BUDGET: Duration = LEASE.dividedBy(2)
        val FIRST_RETRY: Duration = Duration.ofSeconds(5)
        val MAX_BACKOFF: Duration = Duration.ofMinutes(5)
    }
}
