package pharmacy.infra.government

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.get
import io.restassured.RestAssured.given
import io.restassured.RestAssured.post
import io.restassured.http.ContentType
import io.micrometer.core.instrument.MeterRegistry
import io.restassured.response.Response
import jakarta.inject.Inject
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import pharmacy.domain.GovernmentCommit
import pharmacy.domain.MedicationId
import pharmacy.domain.PrescriptionId
import pharmacy.domain.PrescriptionStatus.CALLED
import pharmacy.domain.port.PrescriptionRepository
import pharmacy.domain.usecase.ReserveStock
import pharmacy.domain.usecase.StaffActions
import pharmacy.infra.government.FakeGovernmentApi.Line
import pharmacy.infra.metrics.MetricsRefresh
import pharmacy.infra.persistence.JooqGovernmentCommits
import pharmacy.infra.persistence.Transactions
import pharmacy.infra.persistence.jooq.tables.references.GOVERNMENT_COMMITS
import pharmacy.infra.persistence.jooq.tables.references.PRESCRIPTIONS
import pharmacy.infra.reset
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Checking in with a government prescription, and committing what the patient took once they have it. Real PostgreSQL,
 * and a fake government system (see [FakeGovernmentApi]) that the real HTTP client talks to.
 */
@QuarkusTest
class GovernmentIntegrationTest {
    @Inject lateinit var flyway: Flyway
    @Inject lateinit var db: DSLContext
    @Inject lateinit var reserveStock: ReserveStock
    @Inject lateinit var staff: StaffActions
    @Inject lateinit var relay: GovernmentCommitRelay
    @Inject lateinit var commits: JooqGovernmentCommits
    @Inject lateinit var prescriptions: PrescriptionRepository
    @Inject lateinit var transactions: Transactions
    @Inject lateinit var government: FakeGovernmentApi
    @Inject lateinit var registry: MeterRegistry
    @Inject lateinit var metrics: MetricsRefresh

    @BeforeEach
    fun fresh() {
        flyway.reset()
        government.reset()
        government.prescribe("GOV-1001", Line("amoxicillin", 1, false), Line("ibuprofen", 2, true))
    }

    private fun checkIn(governmentNumber: String?, vararg items: Pair<String, Int>, id: UUID? = null): Response {
        val medications = items.joinToString(",") { (medicationId, quantity) -> """{"medicationId":"$medicationId","quantity":$quantity}""" }
        val number = governmentNumber?.let { ""","governmentNumber":"$it"""" } ?: ""
        val chosen = id?.let { "\"id\":\"$it\"," } ?: ""
        return given().contentType(ContentType.JSON)
            .body("""{$chosen"patientName":"Maria","items":[$medications]$number}""")
            .post("/api/prescriptions")
    }

    /** Checks in, then does what the stock check, the pharmacist, the packager and the counter do. */
    private fun collected(governmentNumber: String?, vararg items: Pair<String, Int>): PrescriptionId {
        val response = checkIn(governmentNumber, *items)
        assertEquals(201, response.statusCode, response.asString())
        val id = response.jsonPath().getString("id")
        reserveStock(PrescriptionId(UUID.fromString(id))) // what the RabbitMQ consumer would do
        for (step in listOf("approve", "packed", "call", "hand-over")) {
            assertEquals(200, post("/api/staff/prescriptions/$id/$step").statusCode, step)
        }
        return PrescriptionId(UUID.fromString(id))
    }

    private fun rows() = db.selectFrom(GOVERNMENT_COMMITS).fetch()

    /** The counters live as long as the application: tests look at what changed, not at the total. */
    private fun calls(operation: String, outcome: String) =
        registry.counter("pharmacy.government.requests", "operation", operation, "outcome", outcome).count()

    private fun timed(operation: String) = registry.get("pharmacy.government.request").tag("operation", operation).timer().count()

    private fun gauge(name: String) = registry.get(name).gauge().value()

    // Looking it up

    @Test
    fun `the patient can look up a government prescription, and sees what is left of it`() {
        val response = get("/api/government-prescriptions/gov-1001")

        assertEquals(200, response.statusCode)
        assertEquals("GOV-1001", response.jsonPath().getString("number"))
        assertEquals(listOf("amoxicillin", "ibuprofen"), response.jsonPath().getList<String>("items.medicationId"))
        assertEquals(listOf(1, 2), response.jsonPath().getList<Int>("items.quantity"))
        assertEquals(listOf(false, true), response.jsonPath().getList<Boolean>("items.maximum"))
    }

    @Test
    fun `what was used up is not offered, and a prescription used up in full says so`() {
        government.prescribe("GOV-2", Line("amoxicillin", 0, false), Line("ibuprofen", 1, true))
        assertEquals(listOf("ibuprofen"), get("/api/government-prescriptions/GOV-2").jsonPath().getList<String>("items.medicationId"))

        // Asking for it anyway (the page does not offer it, but the API can be called by anyone) says it is used up, not that it is missing.
        val usedUp = checkIn("GOV-2", "amoxicillin" to 1)
        assertEquals(400, usedUp.statusCode)
        assertEquals("Government prescription GOV-2 has no Amoxicillin 500 mg left to collect", usedUp.jsonPath().getString("error"))
        assertEquals("Metformin 850 mg is not on government prescription GOV-2", checkIn("GOV-2", "metformin" to 1).jsonPath().getString("error"))

        government.prescribe("GOV-3", Line("amoxicillin", 0, false))
        val used = get("/api/government-prescriptions/GOV-3")
        assertEquals(400, used.statusCode)
        assertEquals("Government prescription GOV-3 has already been used in full", used.jsonPath().getString("error"))
    }

    @Test
    fun `an unknown number is a 404, and an unreachable government system a 502 the patient can read`() {
        assertEquals(404, get("/api/government-prescriptions/NOPE").statusCode)
        assertEquals(400, get("/api/government-prescriptions/${"9".repeat(41)}").statusCode, "longer than any real number")
        assertEquals(1, government.lookups, "only NOPE was asked of the government: the long number never left the app")

        government.down = true
        val unavailable = get("/api/government-prescriptions/GOV-1001")
        assertEquals(502, unavailable.statusCode, "not a 503: the web app retries those, as it does for a database that is down")
        assertTrue("enter your medication by hand" in unavailable.jsonPath().getString("error"))
    }

    // Checking in

    @Test
    fun `check-in is held to the government prescription, as the government has it now`() {
        val tooMuch = checkIn("GOV-1001", "ibuprofen" to 3)
        assertEquals(400, tooMuch.statusCode)
        assertEquals("Government prescription GOV-1001 allows at most 2 packs of Ibuprofen 400 mg", tooMuch.jsonPath().getString("error"))

        assertEquals(400, checkIn("GOV-1001", "metformin" to 1).statusCode)
        assertEquals(404, checkIn("NOPE", "ibuprofen" to 1).statusCode)
        assertEquals(400, checkIn("   ", "ibuprofen" to 1).statusCode, "a number was meant, but none was typed")
        assertEquals(0, db.fetchCount(PRESCRIPTIONS), "nothing was checked in")

        assertEquals(201, checkIn("GOV-1001", "ibuprofen" to 1).statusCode)
    }

    @Test
    fun `check-in with a government prescription keeps the number as the government writes it, and staff see it`() {
        val id = checkIn(" gov-1001 ", "ibuprofen" to 1).jsonPath().getString("id")
        reserveStock(PrescriptionId(UUID.fromString(id)))

        assertEquals(listOf("GOV-1001"), get("/api/staff/queues").jsonPath().getList<String>("pharmacist.governmentNumber"))
    }

    @Test
    fun `without the government system patients can still check in by hand`() {
        government.down = true

        assertEquals(201, checkIn(null, "ibuprofen" to 1).statusCode)
        assertEquals(0, government.lookups, "no number, nothing to look up")
        assertEquals(502, checkIn("GOV-1001", "ibuprofen" to 1).statusCode)
    }

    @Test
    fun `a check-in sent again after it went through returns its ticket, without asking the government system`() {
        val id = UUID.randomUUID()
        val first = checkIn("GOV-1001", "ibuprofen" to 1, id = id)
        assertEquals(201, first.statusCode)
        val lookups = government.lookups
        government.down = true // the response was lost, and by the time the page tries again the government system is gone

        val retry = checkIn("GOV-1001", "ibuprofen" to 1, id = id)

        assertEquals(201, retry.statusCode)
        assertEquals(first.jsonPath().getString("ticketNumber"), retry.jsonPath().getString("ticketNumber"))
        assertEquals(lookups, government.lookups)
        assertEquals(1, db.fetchCount(PRESCRIPTIONS))
    }

    // Committing

    @Test
    fun `a maximum of 2 and the patient takes 1 - the commit says 1, and waits in the outbox before the government hears of it`() {
        val id = collected("GOV-1001", "ibuprofen" to 1)

        val queued = rows().single()
        assertEquals(id.value, queued.prescriptionId)
        assertEquals("GOV-1001", queued.governmentNumber)
        assertEquals("PENDING", queued.status)
        assertEquals(listOf(GovernmentCommit.Item(MedicationId("ibuprofen"), 1)), GovernmentMessages.decodeItems(queued.items.data()))
        assertTrue(government.commits.isEmpty(), "the government has not been called yet")

        assertEquals(1, relay.relay())

        assertEquals(
            listOf(FakeGovernmentApi.ReceivedCommit("GOV-1001", id.toString(), listOf("ibuprofen" to 1))),
            government.commits,
        )
        assertEquals("COMMITTED", rows().single().status)
        assertEquals(0, relay.relay(), "nothing is sent twice")
        assertEquals(1, government.commits.size)
    }

    @Test
    fun `several medications are committed together, at the quantities taken`() {
        collected("GOV-1001", "amoxicillin" to 1, "ibuprofen" to 2)

        relay.relay()

        assertEquals(listOf("amoxicillin" to 1, "ibuprofen" to 2), government.commits.single().items.sortedBy { it.first })
    }

    @Test
    fun `nothing is queued before the patient has their medication, nor for a prescription typed by hand`() {
        val response = checkIn("GOV-1001", "ibuprofen" to 1)
        val id = PrescriptionId(UUID.fromString(response.jsonPath().getString("id")))
        reserveStock(id)
        for (step in listOf("approve", "packed", "call")) post("/api/staff/prescriptions/$id/$step")
        assertEquals(0, rows().size, "called, but not collected yet")

        collected(null, "cetirizine" to 1)
        assertEquals(0, rows().size, "no government prescription, nothing to commit")
        assertEquals(0, relay.relay())
    }

    @Test
    fun `a commit exists only if the hand-over does - both commit, or neither`() {
        val response = checkIn("GOV-1001", "ibuprofen" to 1)
        val id = PrescriptionId(UUID.fromString(response.jsonPath().getString("id")))
        reserveStock(id)
        for (step in listOf("approve", "packed", "call")) post("/api/staff/prescriptions/$id/$step")

        assertFailsWith<IllegalStateException> {
            transactions.transaction("hand over, then fail") {
                staff.handOver(id)
                throw IllegalStateException("the transaction dies after the hand-over")
            }
        }

        assertEquals(0, rows().size)
        assertEquals(CALLED.name, db.select(PRESCRIPTIONS.STATUS).from(PRESCRIPTIONS).fetchSingle().value1())
    }

    @Test
    fun `a no show commits nothing, since the patient took nothing`() {
        val response = checkIn("GOV-1001", "ibuprofen" to 1)
        val id = response.jsonPath().getString("id")
        reserveStock(PrescriptionId(UUID.fromString(id)))
        for (step in listOf("approve", "packed", "call", "no-show")) {
            assertEquals(200, post("/api/staff/prescriptions/$id/$step").statusCode, step)
        }

        assertEquals(0, rows().size)
        assertEquals(0, relay.relay())
        assertEquals(0, government.commits.size)
    }

    @Test
    fun `handing over twice does not queue a second commit`() {
        val id = collected("GOV-1001", "ibuprofen" to 1)

        assertEquals(409, post("/api/staff/prescriptions/$id/hand-over").statusCode)

        assertEquals(1, rows().size)
    }

    // The government is not always there

    @Test
    fun `while the government is down the commit waits, is tried again later with a pause, and goes through when it is back`() {
        val id = collected("GOV-1001", "ibuprofen" to 1)
        government.down = true

        assertEquals(0, relay.relay())
        val waiting = rows().single()
        assertEquals("PENDING", waiting.status)
        assertEquals(1, waiting.attempts)
        assertTrue(waiting.lastError!!.contains("503"), waiting.lastError)
        assertTrue(waiting.nextAttemptAt.toInstant().isAfter(Instant.now()), "not tried again at once")

        assertEquals(0, relay.relay(), "not due yet: the government is left alone")
        assertEquals(1, rows().single().attempts)

        government.down = false
        assertEquals(1, relay.relay(Instant.now().plusSeconds(60)))

        val done = rows().single()
        assertEquals("COMMITTED", done.status)
        assertEquals(2, done.attempts)
        assertNull(done.lastError)
        assertEquals(id.toString(), government.commits.single().commitId)
    }

    @Test
    fun `when the government fails the round stops there, and what it did not send is due again at once`() {
        government.prescribe("GOV-1001", Line("ibuprofen", 5, true)) // room for three tickets of one pack
        repeat(3) { collected("GOV-1001", "ibuprofen" to 1) }
        government.commitStatus = 503

        assertEquals(0, relay.relay())

        assertEquals(1, government.commits.size, "one failure is enough to know")
        val (tried, untouched) = rows().partition { it.attempts == 1 }
        assertEquals(1, tried.size)
        assertTrue(tried.single().nextAttemptAt.toInstant().isAfter(Instant.now()), "the one that failed waits")
        assertEquals(listOf(0, 0), untouched.map { it.attempts }, "the attempts they never had are not counted")
        assertTrue(untouched.all { !it.nextAttemptAt.toInstant().isAfter(Instant.now()) }, "the rest are not held back")

        assertEquals(0, relay.relay())
        assertEquals(2, government.commits.size, "the next round takes the next one")
    }

    @Test
    fun `a commit sent twice carries the same id, so the government can tell`() {
        collected("GOV-1001", "ibuprofen" to 1)
        government.commitStatus = 503 // the government takes it but its answer never gets back, say

        relay.relay()
        government.commitStatus = 201
        relay.relay(Instant.now().plusSeconds(60))

        assertEquals(2, government.commits.size)
        assertEquals(1, government.commits.map { it.commitId }.toSet().size)
    }

    @Test
    fun `a commit the government refuses for good is not sent again, and staff can see it`() {
        collected("GOV-1001", "ibuprofen" to 1)
        government.commitStatus = 409

        assertEquals(0, relay.relay())

        val refused = rows().single()
        assertEquals("REJECTED", refused.status)
        assertTrue(refused.lastError!!.contains("409"), refused.lastError)
        assertEquals(0, relay.relay(Instant.now().plusSeconds(3600)))
        assertEquals(1, government.commits.size)

        val health = get("/health").jsonPath()
        assertEquals(1, health.getInt("rejectedGovernmentCommits"))
        assertEquals(0, health.getInt("pendingGovernmentCommits"))
    }

    @Test
    fun `health counts the commits waiting for the government`() {
        collected("GOV-1001", "ibuprofen" to 1)

        val waiting = get("/health").jsonPath()
        assertEquals(1, waiting.getInt("pendingGovernmentCommits"))
        assertTrue(waiting.getLong("oldestPendingGovernmentCommitSeconds") >= 0)
        assertEquals(1, commits.backlog().pending)

        relay.relay()

        val done = get("/health").jsonPath()
        assertEquals(0, done.getInt("pendingGovernmentCommits"))
        assertNull(done.get<Any>("oldestPendingGovernmentCommitSeconds"))
    }

    @Test
    fun `a commit that was claimed but never recorded is sent again once its lease runs out`() {
        collected("GOV-1001", "ibuprofen" to 1)

        // An instance claims it, then dies before the government is called or the outcome recorded.
        transactions.transaction("claim") { commits.claimDue(Instant.now(), 10, java.time.Duration.ofMinutes(1)) }

        assertEquals(0, relay.relay(), "leased: another instance is on it")
        assertEquals(1, relay.relay(Instant.now().plusSeconds(120)))
        assertEquals(1, government.commits.size)
    }

    @Test
    fun `an answer that came too late does not undo the outcome recorded meanwhile`() {
        val id = collected("GOV-1001", "ibuprofen" to 1)
        assertEquals(1, relay.relay())
        val committed = rows().single()

        // The instance that stalled past its lease answers now: refused, failed, or accepted again.
        assertFalse(commits.markRejected(id, "a late refusal"))
        assertFalse(commits.scheduleRetry(id, Instant.now().plusSeconds(60), "a late failure"))
        assertFalse(commits.markCommitted(id, Instant.now().plusSeconds(60)))

        val after = rows().single()
        assertEquals("COMMITTED", after.status)
        assertNull(after.lastError)
        assertEquals(committed.sentAt, after.sentAt)
        assertEquals(committed.nextAttemptAt, after.nextAttemptAt)
    }

    @Test
    fun `a commit refused for good stays refused, whatever else is recorded after`() {
        val id = collected("GOV-1001", "ibuprofen" to 1)
        government.commitStatus = 409
        relay.relay()
        assertEquals("REJECTED", rows().single().status)

        assertFalse(commits.markCommitted(id, Instant.now()))
        assertFalse(commits.scheduleRetry(id, Instant.now(), "later"))

        assertEquals("REJECTED", rows().single().status)
        assertTrue("answering 409" in rows().single().lastError.orEmpty())
    }

    @Test
    fun `the outcome of a commit is recorded while it is pending`() {
        val id = collected("GOV-1001", "ibuprofen" to 1)

        assertTrue(commits.scheduleRetry(id, Instant.now().plusSeconds(30), "busy"))
        assertEquals("busy", rows().single().lastError)
        assertTrue(commits.markCommitted(id, Instant.now()))
        assertEquals("COMMITTED", rows().single().status)
        assertNull(rows().single().lastError)
    }

    // Two tickets on one government prescription

    @Test
    fun `a second ticket cannot take what the first holds, whichever phone it is on`() {
        assertEquals(201, checkIn("GOV-1001", "ibuprofen" to 2).statusCode)

        val second = checkIn("GOV-1001", "ibuprofen" to 1)

        assertEquals(400, second.statusCode)
        assertEquals(
            "Government prescription GOV-1001 has no Ibuprofen 400 mg left to collect (2 packs of it already on a ticket that is still in progress)",
            second.jsonPath().getString("error"),
        )
        assertEquals(1, db.fetchCount(PRESCRIPTIONS), "the refused check-in took no ticket")
        assertEquals(201, checkIn("GOV-1001", "amoxicillin" to 1).statusCode, "other medication on it is not held")
    }

    @Test
    fun `what the first ticket left is still there for the second`() {
        assertEquals(201, checkIn("GOV-1001", "ibuprofen" to 1).statusCode)

        val tooMuch = checkIn("GOV-1001", "ibuprofen" to 2)
        assertEquals(400, tooMuch.statusCode)
        assertEquals(
            "Government prescription GOV-1001 allows at most 1 pack of Ibuprofen 400 mg (1 pack of it already on a ticket that is still in progress)",
            tooMuch.jsonPath().getString("error"),
        )
        assertEquals(201, checkIn("GOV-1001", "ibuprofen" to 1).statusCode)
    }

    @Test
    fun `a ticket that ends without the medication leaving the shelf holds nothing`() {
        // Rejected by the pharmacist.
        val rejected = checkIn("GOV-1001", "ibuprofen" to 2).jsonPath().getString("id")
        reserveStock(PrescriptionId(UUID.fromString(rejected)))
        assertEquals(400, checkIn("GOV-1001", "ibuprofen" to 2).statusCode, "held while it waits for the pharmacist")
        given().contentType(ContentType.JSON).body("""{"reason":"Allergy on file"}""").post("/api/staff/prescriptions/$rejected/reject")

        // Called, and nobody came.
        val noShow = checkIn("GOV-1001", "ibuprofen" to 2)
        assertEquals(201, noShow.statusCode)
        val noShowId = noShow.jsonPath().getString("id")
        reserveStock(PrescriptionId(UUID.fromString(noShowId)))
        for (step in listOf("approve", "packed", "call")) post("/api/staff/prescriptions/$noShowId/$step")
        assertEquals(400, checkIn("GOV-1001", "ibuprofen" to 2).statusCode, "held while the patient is called")
        post("/api/staff/prescriptions/$noShowId/no-show")

        assertEquals(201, checkIn("GOV-1001", "ibuprofen" to 2).statusCode)
    }

    @Test
    fun `a collected ticket holds its packs until the government has accepted its commit, then the government's figures count them`() {
        collected("GOV-1001", "ibuprofen" to 1) // the commit is waiting in the outbox

        assertEquals(
            "Government prescription GOV-1001 allows at most 1 pack of Ibuprofen 400 mg (1 pack of it already on a ticket that is still in progress)",
            checkIn("GOV-1001", "ibuprofen" to 2).jsonPath().getString("error"),
        )

        // The government hears of it: its figures show 1 left. Counted once, not twice.
        assertEquals(1, relay.relay())
        government.prescribe("GOV-1001", Line("amoxicillin", 1, false), Line("ibuprofen", 1, true))
        val tooMuch = checkIn("GOV-1001", "ibuprofen" to 2)
        assertEquals("Government prescription GOV-1001 allows at most 1 pack of Ibuprofen 400 mg", tooMuch.jsonPath().getString("error"))
        assertEquals(201, checkIn("GOV-1001", "ibuprofen" to 1).statusCode)
    }

    @Test
    fun `what the government accepted after it was asked is not in its figures yet, what it accepted before is`() {
        val id = collected("GOV-1001", "ibuprofen" to 1)
        relay.relay()
        val sentAt = rows().single().sentAt!!.toInstant()

        assertEquals(mapOf(MedicationId("ibuprofen") to 1), prescriptions.promisedOn("GOV-1001", sentAt.minusSeconds(1)), "asked before it accepted")
        assertEquals(emptyMap(), prescriptions.promisedOn("GOV-1001", sentAt.plusSeconds(1)), "asked after: its figures have it")
        assertEquals(emptyMap(), prescriptions.promisedOn("GOV-OTHER", sentAt.minusSeconds(1)), "another prescription")
        assertEquals(id, PrescriptionId(rows().single().prescriptionId))
    }

    @Test
    fun `a commit the government refused for good holds nothing`() {
        collected("GOV-1001", "ibuprofen" to 2)
        assertEquals(400, checkIn("GOV-1001", "ibuprofen" to 1).statusCode)
        government.commitStatus = 409
        relay.relay()

        assertEquals(201, checkIn("GOV-1001", "ibuprofen" to 1).statusCode)
    }

    @Test
    fun `two phones checking in at the same moment on one number - one gets the packs, the other is told`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(10) { round ->
                val number = "GOV-C$round"
                government.prescribe(number, Line("ibuprofen", 2, true))
                val start = CyclicBarrier(2)
                val statuses = (1..2).map {
                    pool.submit<Int> { start.await(); checkIn(number, "ibuprofen" to 2).statusCode }
                }.map { it.get(30, TimeUnit.SECONDS) }

                assertEquals(listOf(201, 400), statuses.sorted(), "round $round")
            }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(10, db.fetchCount(PRESCRIPTIONS))
        assertEquals((1..10).toList(), db.select(PRESCRIPTIONS.TICKET_NUMBER).from(PRESCRIPTIONS).orderBy(PRESCRIPTIONS.TICKET_NUMBER).fetch().map { it.value1() }, "no gap")
    }

    @Test
    fun `a double tap on a government check-in - the same id twice at the same moment - is one ticket, not a refusal`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(10) { round ->
                val number = "GOV-D$round"
                government.prescribe(number, Line("amoxicillin", 1, false))
                val id = UUID.randomUUID()
                val start = CyclicBarrier(2)
                val answers = (1..2).map {
                    pool.submit<Response> { start.await(); checkIn(number, "amoxicillin" to 1, id = id) }
                }.map { it.get(30, TimeUnit.SECONDS) }

                assertEquals(listOf(201, 201), answers.map { it.statusCode }, "round $round: ${answers.map { it.asString() }}")
                assertEquals(1, answers.map { it.jsonPath().getInt("ticketNumber") }.toSet().size, "round $round: the same ticket")
            }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(10, db.fetchCount(PRESCRIPTIONS), "one ticket per round")
        assertEquals((1..10).toList(), db.select(PRESCRIPTIONS.TICKET_NUMBER).from(PRESCRIPTIONS).orderBy(PRESCRIPTIONS.TICKET_NUMBER).fetch().map { it.value1() }, "no gap")
    }

    // What the government sends

    @Test
    fun `medication the pharmacy does not carry is listed, and cannot be checked in`() {
        government.prescribe("GOV-X", Line("zzz", 1, false), Line("ibuprofen", 1, true))

        assertEquals(listOf("zzz", "ibuprofen"), get("/api/government-prescriptions/GOV-X").jsonPath().getList<String>("items.medicationId"))
        val refused = checkIn("GOV-X", "zzz" to 1)
        assertEquals(400, refused.statusCode)
        assertEquals("Unknown medication 'zzz'", refused.jsonPath().getString("error"))
        assertEquals(201, checkIn("GOV-X", "ibuprofen" to 1).statusCode)
    }

    @Test
    fun `medication prescribed as it is can be checked in in more than 10 packs, but only in the full amount`() {
        government.prescribe("GOV-12", Line("ibuprofen", 12, false))

        val some = checkIn("GOV-12", "ibuprofen" to 10)
        assertEquals(400, some.statusCode)
        assertEquals("Government prescription GOV-12 covers 12 packs of Ibuprofen 400 mg, no more and no less", some.jsonPath().getString("error"))
        assertEquals(201, checkIn("GOV-12", "ibuprofen" to 12).statusCode)
    }

    @Test
    fun `the same medication listed twice on a government check-in is refused as it is without one`() {
        val refused = checkIn("GOV-1001", "ibuprofen" to 1, "ibuprofen" to 1)

        assertEquals(400, refused.statusCode)
        assertEquals("Each medication can only be listed once", refused.jsonPath().getString("error"))
        assertEquals(0, db.fetchCount(PRESCRIPTIONS))
    }

    @Test
    fun `no packs at all is told as a quantity, not as the prescription's maximum`() {
        val refused = checkIn("GOV-1001", "ibuprofen" to 0)

        assertEquals(400, refused.statusCode)
        assertEquals("Quantity of Ibuprofen 400 mg must be at least 1", refused.jsonPath().getString("error"))
    }

    @Test
    fun `an answer that cannot be read is a government system we cannot rely on, told as such`() {
        government.lookupBody = "this is not a prescription"

        val answer = get("/api/government-prescriptions/GOV-1001")

        assertEquals(502, answer.statusCode)
        assertTrue("enter your medication by hand" in answer.jsonPath().getString("error"))
        assertEquals(502, checkIn("GOV-1001", "ibuprofen" to 1).statusCode)
        assertEquals(0, db.fetchCount(PRESCRIPTIONS))
    }

    @Test
    fun `a government system that answers too slowly is given up on`() {
        val failed = calls("lookup", "failed")
        government.lookupDelayMs = 3_000 // the test profile waits 1s

        val started = System.nanoTime()
        val answer = get("/api/government-prescriptions/GOV-1001")
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(502, answer.statusCode)
        assertTrue(tookMs < 2_500, "gave up after its own timeout, not after the government's answer: $tookMs ms")
        assertEquals(failed + 1, calls("lookup", "failed"))
    }

    // Metrics

    @Test
    fun `lookups are counted by how they ended and timed, and the government is up while it answers`() {
        val found = calls("lookup", "found")
        val notFound = calls("lookup", "not_found")
        val failed = calls("lookup", "failed")
        val timedBefore = timed("lookup")

        get("/api/government-prescriptions/GOV-1001")
        assertEquals(1.0, gauge("pharmacy.government.up"))
        get("/api/government-prescriptions/NOPE")
        assertEquals(1.0, gauge("pharmacy.government.up"), "no such prescription is still an answer")
        government.down = true
        get("/api/government-prescriptions/GOV-1001")
        assertEquals(0.0, gauge("pharmacy.government.up"))
        government.down = false
        get("/api/government-prescriptions/GOV-1001")
        assertEquals(1.0, gauge("pharmacy.government.up"), "up again with the next answer")

        assertEquals(2.0, calls("lookup", "found") - found)
        assertEquals(1.0, calls("lookup", "not_found") - notFound)
        assertEquals(1.0, calls("lookup", "failed") - failed)
        assertEquals(4, timed("lookup") - timedBefore)
    }

    @Test
    fun `commits are counted by how they ended`() {
        val accepted = calls("commit", "accepted")
        val refused = calls("commit", "refused")
        val failed = calls("commit", "failed")
        val timedBefore = timed("commit")

        collected("GOV-1001", "ibuprofen" to 1)
        government.commitStatus = 503
        relay.relay()
        government.commitStatus = 201
        relay.relay(Instant.now().plusSeconds(60))
        collected("GOV-1001", "ibuprofen" to 1)
        government.commitStatus = 409
        relay.relay()

        assertEquals(1.0, calls("commit", "failed") - failed)
        assertEquals(1.0, calls("commit", "accepted") - accepted)
        assertEquals(1.0, calls("commit", "refused") - refused)
        assertEquals(3, timed("commit") - timedBefore)
        assertEquals(1.0, gauge("pharmacy.government.up"), "a refusal is an answer")
    }

    @Test
    fun `the commit outbox shows as gauges`() {
        collected("GOV-1001", "ibuprofen" to 1)
        metrics.refresh()
        assertEquals(1.0, gauge("pharmacy.government.commits.pending"))
        assertTrue(gauge("pharmacy.government.commits.oldest.pending.age") >= 0.0)
        assertEquals(0.0, gauge("pharmacy.government.commits.rejected"))

        government.commitStatus = 409
        relay.relay()
        metrics.refresh()

        assertEquals(0.0, gauge("pharmacy.government.commits.pending"))
        assertEquals(0.0, gauge("pharmacy.government.commits.oldest.pending.age"))
        assertEquals(1.0, gauge("pharmacy.government.commits.rejected"))
    }

    @Test
    fun `Prometheus can scrape them`() {
        get("/api/government-prescriptions/GOV-1001")
        metrics.refresh()

        val scraped = get("/q/metrics").asString()

        for (expected in listOf(
            """pharmacy_government_requests_total{operation="lookup",outcome="found"}""",
            """pharmacy_government_requests_total{operation="commit",outcome="accepted"}""",
            """pharmacy_government_request_seconds_bucket{operation="lookup",le="0.01"}""",
            "pharmacy_government_up 1.0",
            "pharmacy_government_commits_pending 0.0",
            "pharmacy_government_commits_oldest_pending_age_seconds 0.0",
            "pharmacy_government_commits_rejected 0.0",
        )) {
            assertTrue(expected in scraped, "$expected is missing from /q/metrics")
        }
    }
}
