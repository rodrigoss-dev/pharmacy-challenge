package pharmacy.infra.api

import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.get
import io.restassured.RestAssured.given
import io.restassured.RestAssured.post
import io.restassured.http.ContentType
import org.junit.jupiter.api.Timeout
import java.net.URL
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The app, started against a database that is not there: Dev Services off, and nothing listening on the port. */
class DatabaseDownProfile : QuarkusTestProfile {
    override fun getConfigOverrides() = mapOf(
        "quarkus.datasource.devservices.enabled" to "false",
        "quarkus.datasource.jdbc.url" to "jdbc:postgresql://localhost:1/pharmacy",
        "quarkus.datasource.username" to "pharmacy",
        "quarkus.datasource.password" to "pharmacy",
        "quarkus.flyway.migrate-at-start" to "false",
    )
}

/**
 * What the people using the system get while the database is unreachable: the API answers at once with a 503 that says
 * when to try again, `/health` still answers, and the live screens say their updates are delayed.
 */
@QuarkusTest
@TestProfile(DatabaseDownProfile::class)
class DatabaseDownTest {
    @TestHTTPResource("/")
    lateinit var baseUrl: URL

    private val unavailable = "The pharmacy system is unavailable for a moment. Please try again in a few seconds."

    private fun assertUnavailable(response: io.restassured.response.Response, what: String) {
        assertEquals(503, response.statusCode, what)
        assertEquals("3", response.header("Retry-After"), what)
        assertEquals(unavailable, response.jsonPath().getString("error"), what)
        assertEquals(setOf("error"), response.jsonPath().getMap<String, Any>("").keys, what)
    }

    @Test
    fun `everything that needs the database answers 503, with a message to read and a time to try again`() {
        assertUnavailable(get("/api/medications"), "catalog")
        assertUnavailable(get("/api/board"), "board")
        assertUnavailable(get("/api/staff/queues"), "queues")
        assertUnavailable(get("/api/prescriptions/${UUID.randomUUID()}"), "tracking")
        assertUnavailable(
            given().contentType(ContentType.JSON).body("""{"patientName":"Maria","items":[{"medicationId":"ibuprofen","quantity":1}]}""").post("/api/prescriptions"),
            "check-in",
        )
        assertUnavailable(post("/api/staff/prescriptions/${UUID.randomUUID()}/approve"), "approve")
        assertUnavailable(given().contentType(ContentType.JSON).body("""{"quantity":5}""").post("/api/staff/medications/ibuprofen/restock"), "restock")
    }

    @Test
    fun `requests are refused at once after the first, not each after its own timeout`() {
        get("/api/medications") // finds out

        val started = System.nanoTime()
        repeat(20) { assertEquals(503, get("/api/board").statusCode) }
        val tookMs = (System.nanoTime() - started) / 1_000_000

        assertTrue(tookMs < 2_000, "20 refusals took $tookMs ms")
    }

    @Test
    fun `a request that is wrong is still told so, since that needs no database`() {
        val invalid = get("/api/prescriptions/not-an-id")
        assertEquals(400, invalid.statusCode)
        assertEquals("Invalid prescription id", invalid.jsonPath().getString("error"))
        assertEquals(404, get("/api/no-such-thing").statusCode)
    }

    @Test
    fun `health still answers, and says the database is down`() {
        val health = get("/health")

        assertEquals(200, health.statusCode)
        val json = health.jsonPath()
        assertEquals("down", json.getString("database"))
        assertEquals("down", json.getString("broker"))
        for (unknown in listOf("pendingEvents", "oldestPendingEventSeconds", "stalledStockChecks", "pendingGovernmentCommits", "oldestPendingGovernmentCommitSeconds", "rejectedGovernmentCommits", "deadLetters")) {
            assertEquals(null, json.get<Any>(unknown), unknown)
        }
        assertEquals(0, json.getInt("liveStreams"))
    }

    @Test
    fun `the orchestrator is told the instance is alive but cannot take traffic`() {
        get("/api/medications") // finds out

        assertEquals(200, get("/q/health/live").statusCode, "restarting it would not bring the database back")
        assertEquals(503, get("/q/health/ready").statusCode)
    }

    @Test
    @Timeout(20)
    fun `a live screen is told its updates are delayed, and stays connected`() {
        val board = firstEvent(baseUrl, "/api/board/live")

        assertEquals("unavailable", board["event"])
        assertEquals("""{"error":"We can't reach the pharmacy system right now. This page catches up by itself."}""", board["data"])

        val tracking = firstEvent(baseUrl, "/api/prescriptions/${UUID.randomUUID()}/live")
        assertEquals("unavailable", tracking["event"])
    }
}
