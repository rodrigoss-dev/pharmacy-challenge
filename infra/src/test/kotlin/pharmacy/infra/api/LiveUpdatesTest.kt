package pharmacy.infra.api

import io.smallrye.mutiny.Multi
import io.smallrye.mutiny.helpers.test.AssertSubscriber
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor
import pharmacy.domain.NotFoundException
import pharmacy.domain.PrescriptionId
import pharmacy.domain.PrescriptionStatus
import pharmacy.domain.PrescriptionStatusChanged
import pharmacy.infra.messaging.PrescriptionChangeFeed
import pharmacy.infra.persistence.DatabaseFailure
import pharmacy.infra.persistence.DatabaseUnavailableException
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LiveUpdatesTest {
    private val changes = BroadcastProcessor.create<PrescriptionStatusChanged>()
    private val feed = object : PrescriptionChangeFeed {
        override val changes: Multi<PrescriptionStatusChanged> = this@LiveUpdatesTest.changes
    }
    private val anyChange = PrescriptionStatusChanged(UUID.randomUUID(), PrescriptionId.random(), 1, PrescriptionStatus.RECEIVED, Instant.EPOCH)
    private val liveUpdates = LiveUpdates(feed, refreshEvery = Duration.ofMillis(500), maxConcurrentQueries = 2)

    @Test
    fun `emits straight away, on every change, and on the safety-net refresh, skipping repeats`() {
        val value = AtomicReference("received")
        val seen = liveUpdates.watch("test") { value.get() }.subscribe().withSubscriber(AssertSubscriber.create(10))

        seen.awaitItems(1).assertItems(LiveUpdate.Value("received"))

        value.set("approved")
        changes.onNext(anyChange)
        seen.awaitItems(2).assertItems(LiveUpdate.Value("received"), LiveUpdate.Value("approved"))

        changes.onNext(anyChange) // nothing changed for this query: no duplicate
        Thread.sleep(200)
        seen.assertItems(LiveUpdate.Value("received"), LiveUpdate.Value("approved"))

        value.set("packed") // changed, but the event got lost (e.g. broker down)
        seen.awaitItems(3, Duration.ofSeconds(2))
            .assertItems(LiveUpdate.Value("received"), LiveUpdate.Value("approved"), LiveUpdate.Value("packed"))
        seen.cancel()
    }

    @Test
    fun `a failing query says so once and the stream carries on when it works again`() {
        val value = AtomicReference<String?>("received")
        val unreachable = DatabaseUnavailableException("test", DatabaseFailure.UNAVAILABLE, SQLException("Connection refused", "08001"))
        val seen = liveUpdates.watch("test") { value.get() ?: throw unreachable }.subscribe().withSubscriber(AssertSubscriber.create(10))
        seen.awaitItems(1)

        value.set(null) // the database goes away
        changes.onNext(anyChange)
        changes.onNext(anyChange) // still away: no second "unavailable"
        seen.awaitItems(2)
        Thread.sleep(200)

        value.set("approved") // back
        seen.awaitItems(3, Duration.ofSeconds(2))
            .assertItems(LiveUpdate.Value("received"), LiveUpdate.Unavailable, LiveUpdate.Value("approved"))
        seen.assertNotTerminated()
        seen.cancel()
    }

    @Test
    fun `a prescription that disappears ends the stream`() {
        val seen = liveUpdates.watch("test") { throw NotFoundException("Prescription gone") }.subscribe()
            .withSubscriber(AssertSubscriber.create(10))

        seen.awaitFailure()
        assertIs<NotFoundException>(seen.failure)
        assertEquals(0, liveUpdates.openStreams)
    }
}
