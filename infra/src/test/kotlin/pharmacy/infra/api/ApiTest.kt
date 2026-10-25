package pharmacy.infra.api

import io.quarkus.test.common.http.TestHTTPResource
import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.get
import io.restassured.RestAssured.given
import io.restassured.RestAssured.post
import io.restassured.http.ContentType
import io.restassured.response.Response
import jakarta.inject.Inject
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Timeout
import pharmacy.domain.PrescriptionId
import pharmacy.domain.usecase.ReserveStock
import pharmacy.infra.reset
import java.net.URL
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The HTTP API with a real database and no broker: the stock check is triggered by hand. */
@QuarkusTest
class ApiTest {
    @Inject lateinit var flyway: Flyway
    @Inject lateinit var reserveStock: ReserveStock

    @TestHTTPResource("/")
    lateinit var baseUrl: URL

    @BeforeEach
    fun freshDatabase() = flyway.reset()

    private fun checkIn(body: String): Response = given().contentType(ContentType.JSON).body(body).post("/api/prescriptions")

    @Test
    fun `patient checks in, gets a ticket and follows it to the counter`() {
        val response = checkIn("""{"patientName":"Maria","items":[{"medicationId":"amoxicillin","quantity":2}]}""")
        assertEquals(201, response.statusCode)
        val id = response.jsonPath().getString("id")
        assertEquals(1, response.jsonPath().getInt("ticketNumber"))
        assertEquals("/api/prescriptions/$id", response.header("Location"))

        assertEquals("RECEIVED", get("/api/prescriptions/$id").jsonPath().getString("status"))

        reserveStock(PrescriptionId(UUID.fromString(id))) // what the RabbitMQ consumer would do
        assertEquals(listOf(id), get("/api/staff/queues").jsonPath().getList<String>("pharmacist.id"))

        for (step in listOf("approve", "packed", "call", "hand-over")) {
            assertEquals(200, post("/api/staff/prescriptions/$id/$step").statusCode, step)
        }
        assertEquals("COLLECTED", get("/api/prescriptions/$id").jsonPath().getString("status"))
        assertEquals("""{"nowCalling":[],"ready":[],"inProgress":[]}""", get("/api/board").asString())
    }

    @Test
    fun `a number that is called and not answered leaves the board, and the patient is told`() {
        val id = checkIn("""{"patientName":"Maria","items":[{"medicationId":"ibuprofen","quantity":1}]}""").jsonPath().getString("id")
        reserveStock(PrescriptionId(UUID.fromString(id)))
        for (step in listOf("approve", "packed")) post("/api/staff/prescriptions/$id/$step")
        assertEquals(409, post("/api/staff/prescriptions/$id/no-show").statusCode, "not called yet")

        post("/api/staff/prescriptions/$id/call")
        assertEquals(listOf(1), get("/api/board").jsonPath().getList<Int>("nowCalling"))
        assertEquals(listOf(id), get("/api/staff/queues").jsonPath().getList<String>("pickup.id"))

        val noShow = post("/api/staff/prescriptions/$id/no-show")
        assertEquals(200, noShow.statusCode)
        assertEquals("NO_SHOW", noShow.jsonPath().getString("status"))

        assertEquals("NO_SHOW", get("/api/prescriptions/$id").jsonPath().getString("status"))
        assertEquals("""{"nowCalling":[],"ready":[],"inProgress":[]}""", get("/api/board").asString())
        assertEquals(emptyList(), get("/api/staff/queues").jsonPath().getList<String>("pickup.id"))
        assertEquals(409, post("/api/staff/prescriptions/$id/hand-over").statusCode, "too late")
    }

    @Test
    fun `errors map to status codes with a readable message`() {
        val invalid = checkIn("""{"patientName":" ","items":[{"medicationId":"amoxicillin","quantity":1}]}""")
        assertEquals(400, invalid.statusCode)
        assertEquals("Please tell us your name", invalid.jsonPath().getString("error"))

        assertEquals(400, checkIn("not json").statusCode)
        assertEquals(400, get("/api/prescriptions/not-a-uuid").statusCode)
        assertEquals(404, get("/api/prescriptions/${UUID.randomUUID()}").statusCode)

        val id = checkIn("""{"patientName":"Maria","items":[{"medicationId":"ibuprofen","quantity":1}]}""").jsonPath().getString("id")
        val conflict = post("/api/staff/prescriptions/$id/packed")
        assertEquals(409, conflict.statusCode)
        assertEquals("Prescription #1 is waiting for its stock check, so that step is not possible now", conflict.jsonPath().getString("error"))
    }

    @Test
    fun `a check-in that cannot be read says what is wrong with it, in the same shape as every other error`() {
        fun refused(body: String, message: String) {
            val response = checkIn(body)
            assertEquals(400, response.statusCode, body)
            assertEquals(message, response.jsonPath().getString("error"), body)
            assertEquals(listOf("error"), response.jsonPath().getMap<String, Any>("").keys.toList(), body)
        }

        // Nulls, where the business rules have something to say about them.
        refused("""{"patientName":null,"items":[{"medicationId":"ibuprofen","quantity":1}]}""", "Please tell us your name")
        refused("""{"patientName":"Maria","items":null}""", "A prescription needs at least one medication")
        refused("""{"patientName":"Maria","items":[null]}""", "Each medication needs a medication id and a number of packs")
        // The rest: said where it is.
        refused(
            """{"patientName":"Maria","items":[{"medicationId":null,"quantity":1}]}""",
            "The request has a missing or invalid value for 'items[0].medicationId'",
        )
        refused(
            """{"patientName":"Maria","items":[{"medicationId":"ibuprofen","quantity":"two"}]}""",
            "The request has a missing or invalid value for 'items[0].quantity'",
        )
        // Not 1: half a pack is not a quantity, and cutting it down silently would hand out something else than asked for.
        refused(
            """{"patientName":"Maria","items":[{"medicationId":"ibuprofen","quantity":1.5}]}""",
            "The request has a missing or invalid value for 'items[0].quantity'",
        )

        // None of them took a ticket number.
        assertEquals(1, checkIn("""{"patientName":"Maria","items":[{"medicationId":"ibuprofen","quantity":1}]}""").jsonPath().getInt("ticketNumber"))
    }

    @Test
    fun `a rejection's reason is short enough to read on a phone`() {
        val id = checkIn("""{"patientName":"Maria","items":[{"medicationId":"ibuprofen","quantity":1}]}""").jsonPath().getString("id")
        reserveStock(PrescriptionId(UUID.fromString(id)))
        fun reject(reason: String) = given().contentType(ContentType.JSON).body("""{"reason":"$reason"}""").post("/api/staff/prescriptions/$id/reject")

        val tooLong = reject("x".repeat(201))
        assertEquals(400, tooLong.statusCode)
        assertEquals("The reason is too long: please use at most 200 characters", tooLong.jsonPath().getString("error"))
        assertEquals("AWAITING_APPROVAL", get("/api/prescriptions/$id").jsonPath().getString("status"))

        assertEquals(200, reject("x".repeat(200)).statusCode)
    }

    @Test
    @Timeout(10)
    fun `live streams send named JSON events`() {
        val board = firstEvent(baseUrl, "/api/board/live")
        assertEquals("board", board["event"])
        assertEquals("""{"nowCalling":[],"ready":[],"inProgress":[]}""", board["data"])

        val id = checkIn("""{"patientName":"Maria","items":[{"medicationId":"ibuprofen","quantity":1}]}""").jsonPath().getString("id")
        val status = firstEvent(baseUrl, "/api/prescriptions/$id/live")
        assertEquals("status", status["event"])
        assertTrue(""""status":"RECEIVED"""" in status["data"]!!, status["data"])
    }

    @Test
    @Timeout(10)
    fun `a live stream for an unknown prescription says so and ends`() {
        val events = sse(baseUrl, "/api/prescriptions/${UUID.randomUUID()}/live").use { it.toList() }.toEvents()

        assertEquals("not-found", events.single()["event"])
        assertTrue(events.single()["data"]!!.startsWith("""{"error":"Prescription"""), events.single()["data"])
    }

    @Test
    fun `health is served, and the backend serves nothing else`() {
        assertEquals(
            """{"database":"up","broker":"down","brokerBlocked":null,"pendingEvents":0,"oldestPendingEventSeconds":null,""" +
                """"stalledStockChecks":0,"deadLetters":null,"pendingGovernmentCommits":0,""" +
                """"oldestPendingGovernmentCommitSeconds":null,"rejectedGovernmentCommits":0,"liveStreams":0}""",
            get("/health").asString(),
        )
        assertEquals(404, get("/api/no-such-thing").statusCode)
        assertEquals(404, get("/").statusCode)
    }

    @Test
    fun `orchestrator health checks say alive and ready without a broker, which only shows as unwell`() {
        val live = get("/q/health/live")
        assertEquals(200, live.statusCode)
        assertTrue("governmentCommitRelay" in live.asString(), "the government commit relay is watched too: ${live.asString()}")
        val ready = get("/q/health/ready")
        assertEquals(200, ready.statusCode, ready.asString())
        assertEquals(503, get("/q/health/well").statusCode) // RabbitMQ is disabled in tests
    }

    @Test
    fun `waiting events are counted, with the age of the oldest`() {
        checkIn("""{"patientName":"Maria","items":[{"medicationId":"ibuprofen","quantity":1}]}""")
        val health = get("/health").jsonPath()
        assertEquals(1, health.getInt("pendingEvents"))
        assertTrue(health.getLong("oldestPendingEventSeconds") >= 0)
    }

    @Test
    fun `the web app's origin may call the API, other origins may not`() {
        val preflight = given()
            .header("Origin", "http://localhost:5173")
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "content-type")
            .options("/api/prescriptions")
        assertEquals(200, preflight.statusCode)
        assertEquals("http://localhost:5173", preflight.header("Access-Control-Allow-Origin"))

        val allowed = given().header("Origin", "http://localhost:5173").get("/api/board")
        assertEquals("http://localhost:5173", allowed.header("Access-Control-Allow-Origin"))
        // Without this the page cannot read Retry-After from the 503s it is told to wait for.
        assertTrue("Retry-After" in allowed.header("Access-Control-Expose-Headers").orEmpty(), allowed.headers.toString())

        val stranger = given().header("Origin", "https://elsewhere.example").get("/api/board")
        assertEquals(null, stranger.header("Access-Control-Allow-Origin"))
    }

    // The medication catalog and restocking

    private fun json(body: String) = given().contentType(ContentType.JSON).body(body)

    /** Checks in for [medication] and has the stock check done, as the consumer would: the pharmacist's queue. */
    private fun waitingForPharmacist(medication: String = "ibuprofen", packs: Int = 1, name: String = "Maria"): String {
        val id = checkIn("""{"patientName":"$name","items":[{"medicationId":"$medication","quantity":$packs}]}""").jsonPath().getString("id")
        reserveStock(PrescriptionId(UUID.fromString(id)))
        return id
    }

    @Test
    fun `the catalog lists every medication by name, with what is on the shelf`() {
        val response = get("/api/medications")

        assertEquals(200, response.statusCode)
        val names = response.jsonPath().getList<String>("name")
        assertEquals(names.sorted(), names)
        assertEquals(8, names.size)
        assertEquals(
            mapOf("id" to "atorvastatin", "name" to "Atorvastatin 20 mg", "category" to "Cholesterol", "stock" to 3),
            response.jsonPath().getList<Map<String, Any>>("").single { it["id"] == "atorvastatin" },
        )
        assertEquals(0, response.jsonPath().getInt("find { it.id == 'salbutamol' }.stock"))
    }

    @Test
    fun `restocking adds the packs, and answers with the medication as it is now`() {
        val restocked = json("""{"quantity":10}""").post("/api/staff/medications/salbutamol/restock")

        assertEquals(200, restocked.statusCode)
        assertEquals(mapOf("id" to "salbutamol", "name" to "Salbutamol inhaler", "category" to "Asthma", "stock" to 10), restocked.jsonPath().getMap<String, Any>(""))
        assertEquals(10, get("/api/medications").jsonPath().getInt("find { it.id == 'salbutamol' }.stock"))
        assertEquals(1010, json("""{"quantity":1000}""").post("/api/staff/medications/salbutamol/restock").jsonPath().getInt("stock"))
    }

    @Test
    fun `a restock is refused when it asks for nothing sensible, and changes nothing`() {
        fun refused(body: String?, status: Int, message: String) {
            val response = json(body.orEmpty()).post("/api/staff/medications/ibuprofen/restock")
            assertEquals(status, response.statusCode, "$body")
            assertEquals(message, response.jsonPath().getString("error"), "$body")
        }

        refused(null, 400, "Say how many packs to add")
        refused("{}", 400, "Say how many packs to add")
        refused("""{"quantity":null}""", 400, "Say how many packs to add")
        refused("""{"quantity":0}""", 400, "Restock between 1 and 1000 packs")
        refused("""{"quantity":-5}""", 400, "Restock between 1 and 1000 packs")
        refused("""{"quantity":1001}""", 400, "Restock between 1 and 1000 packs")
        refused("""{"quantity":"ten"}""", 400, "The request has a missing or invalid value for 'quantity'")
        refused("""{"quantity":2.5}""", 400, "The request has a missing or invalid value for 'quantity'")
        assertEquals(60, get("/api/medications").jsonPath().getInt("find { it.id == 'ibuprofen' }.stock"))

        val unknown = json("""{"quantity":5}""").post("/api/staff/medications/nope/restock")
        assertEquals(404, unknown.statusCode)
        assertEquals("Medication nope not found", unknown.jsonPath().getString("error"))
    }

    // Staff steps

    @Test
    fun `staff can run the stock check by hand, and doing it twice changes nothing`() {
        val id = checkIn("""{"patientName":"Maria","items":[{"medicationId":"atorvastatin","quantity":2}]}""").jsonPath().getString("id")
        assertEquals(listOf(id), get("/api/staff/queues").jsonPath().getList<String>("stockCheck.id"))

        val first = post("/api/staff/prescriptions/$id/retry-stock-check")
        assertEquals(200, first.statusCode)
        assertEquals("AWAITING_APPROVAL", first.jsonPath().getString("status"))
        val second = post("/api/staff/prescriptions/$id/retry-stock-check")
        assertEquals(200, second.statusCode)
        assertEquals("AWAITING_APPROVAL", second.jsonPath().getString("status"))

        assertEquals(listOf(id), get("/api/staff/queues").jsonPath().getList<String>("pharmacist.id"))
        assertEquals(1, get("/api/medications").jsonPath().getInt("find { it.id == 'atorvastatin' }.stock"), "reserved once")
    }

    @Test
    fun `a rejection with no reason is refused in words, a null or missing reason too`() {
        val id = waitingForPharmacist()
        fun reject(body: String?) = json(body.orEmpty()).post("/api/staff/prescriptions/$id/reject")

        for (body in listOf(null, "{}", """{"reason":null}""", """{"reason":"   "}""")) {
            val response = reject(body)
            assertEquals(400, response.statusCode, "$body")
            assertEquals("A reason is required to reject a prescription", response.jsonPath().getString("error"), "$body")
        }
        assertEquals("AWAITING_APPROVAL", get("/api/prescriptions/$id").jsonPath().getString("status"))
    }

    @Test
    fun `a rejection of a prescription that somebody else already approved says where it is, whatever the reason`() {
        val id = waitingForPharmacist()
        post("/api/staff/prescriptions/$id/approve")

        val response = json("").post("/api/staff/prescriptions/$id/reject") // no reason either

        assertEquals(409, response.statusCode)
        assertEquals("Prescription #1 is waiting to be packed, so that step is not possible now", response.jsonPath().getString("error"))
    }

    @Test
    fun `every staff step says so when the prescription does not exist, or the id is not one`() {
        val unknown = UUID.randomUUID()
        for (step in listOf("approve", "reject", "packed", "call", "hand-over", "no-show", "retry-stock-check")) {
            val missing = json("""{"reason":"x"}""").post("/api/staff/prescriptions/$unknown/$step")
            assertEquals(404, missing.statusCode, step)
            assertEquals("Prescription $unknown not found", missing.jsonPath().getString("error"), step)

            val invalid = json("""{"reason":"x"}""").post("/api/staff/prescriptions/not-an-id/$step")
            assertEquals(400, invalid.statusCode, step)
            assertEquals("Invalid prescription id", invalid.jsonPath().getString("error"), step)
        }
    }

    // Check-in

    @Test
    fun `a check-in says what is wrong with the medication`() {
        fun refused(body: String, message: String, status: Int = 400) {
            val response = checkIn(body)
            assertEquals(status, response.statusCode, body)
            assertEquals(message, response.jsonPath().getString("error"), body)
        }

        refused("""{"patientName":"Maria","items":[{"medicationId":"nope","quantity":1}]}""", "Unknown medication 'nope'")
        refused(
            """{"patientName":"Maria","items":[{"medicationId":"ibuprofen","quantity":1},{"medicationId":"ibuprofen","quantity":2}]}""",
            "Each medication can only be listed once",
        )
        refused("""{"patientName":"Maria","items":[]}""", "A prescription needs at least one medication")
        refused("""{"patientName":"Maria","items":[{"medicationId":"ibuprofen","quantity":0}]}""", "Quantity of Ibuprofen 400 mg must be at least 1")
        refused("""{"patientName":"Maria","items":[{"medicationId":"ibuprofen"}]}""", "Quantity of Ibuprofen 400 mg must be at least 1")
        refused("""{"id":"abc","patientName":"Maria","items":[{"medicationId":"ibuprofen","quantity":1}]}""", "Invalid prescription id")
        assertEquals(1, checkIn("""{"patientName":"Maria","items":[{"medicationId":"ibuprofen","quantity":1}]}""").jsonPath().getInt("ticketNumber"), "none took a ticket")
    }

    @Test
    fun `the name is kept without the spaces around it`() {
        val id = checkIn("""{"patientName":"   Maria Silva ","items":[{"medicationId":"ibuprofen","quantity":1}]}""").jsonPath().getString("id")

        assertEquals("Maria Silva", get("/api/prescriptions/$id").jsonPath().getString("patientName"))
    }

    @Test
    fun `a check-in sent again gets its ticket back, and the same id for other details is a conflict`() {
        val id = UUID.randomUUID()
        fun send(name: String, medication: String, packs: Int) =
            checkIn("""{"id":"$id","patientName":"$name","items":[{"medicationId":"$medication","quantity":$packs}]}""")

        val first = send("Maria", "ibuprofen", 2)
        val again = send(" Maria ", "ibuprofen", 2)
        assertEquals(201, first.statusCode)
        assertEquals(201, again.statusCode)
        assertEquals(first.jsonPath().getString("ticketNumber"), again.jsonPath().getString("ticketNumber"))

        for (other in listOf(send("Maria", "ibuprofen", 1), send("Maria", "cetirizine", 2), send("Joao", "ibuprofen", 2))) {
            assertEquals(409, other.statusCode)
            assertEquals("This check-in was already sent with other details. Please start a new check-in.", other.jsonPath().getString("error"))
        }
        assertEquals(1, get("/health").jsonPath().getInt("pendingEvents"), "one check-in")
        assertEquals(2, get("/api/prescriptions/$id").jsonPath().getList<Int>("items.quantity").single())
    }

    @Test
    @Timeout(60)
    fun `a double tap - the same check-in twice at the same moment - makes one ticket, and leaves no gap in the numbers`() {
        val pool = Executors.newFixedThreadPool(2)
        try {
            repeat(10) { round ->
                val id = UUID.randomUUID()
                val start = CyclicBarrier(2)
                val answers = (1..2).map {
                    pool.submit<Response> {
                        start.await()
                        checkIn("""{"id":"$id","patientName":"Maria","items":[{"medicationId":"cetirizine","quantity":1}]}""")
                    }
                }.map { it.get(30, TimeUnit.SECONDS) }

                assertEquals(listOf(201, 201), answers.map { it.statusCode }, "round $round")
                assertEquals(1, answers.map { it.jsonPath().getInt("ticketNumber") }.toSet().size, "round $round: the same ticket")
                assertEquals(round + 1, answers.first().jsonPath().getInt("ticketNumber"), "round $round: no number lost")
            }
        } finally {
            pool.shutdownNow()
        }
    }

    // Who sees what

    @Test
    fun `the waiting room board shows the numbers of every stage in the order they were drawn`() {
        val ana = waitingForPharmacist("ibuprofen", name = "Ana") // 1, with the pharmacist
        val bia = waitingForPharmacist("ibuprofen", name = "Bia") // 2, packed
        val cris = waitingForPharmacist("cetirizine", name = "Cris") // 3, called
        val dani = waitingForPharmacist("amoxicillin", name = "Dani") // 4, packed
        checkIn("""{"patientName":"Eva","items":[{"medicationId":"metformin","quantity":1}]}""") // 5, stock check not done
        for (step in listOf("approve", "packed")) post("/api/staff/prescriptions/$bia/$step")
        for (step in listOf("approve", "packed", "call")) post("/api/staff/prescriptions/$cris/$step")
        for (step in listOf("approve", "packed")) post("/api/staff/prescriptions/$dani/$step")

        val board = get("/api/board").jsonPath()

        assertEquals(listOf(3), board.getList<Int>("nowCalling"))
        assertEquals(listOf(2, 4), board.getList<Int>("ready"))
        assertEquals(listOf(1, 5), board.getList<Int>("inProgress"))
        assertEquals(setOf("nowCalling", "ready", "inProgress"), board.getMap<String, Any>("").keys, "numbers only: no names")
        assertEquals(listOf(ana), get("/api/staff/queues").jsonPath().getList<String>("pharmacist.id"))
    }

    @Test
    fun `staff queues list the oldest ticket first, with the names`() {
        val ids = listOf("Ana", "Bia", "Cris").map { waitingForPharmacist("ibuprofen", name = it) }
        val eva = checkIn("""{"patientName":"Eva","items":[{"medicationId":"cetirizine","quantity":1}]}""").jsonPath().getString("id")

        val queues = get("/api/staff/queues").jsonPath()

        assertEquals(ids, queues.getList<String>("pharmacist.id"))
        assertEquals(listOf("Ana", "Bia", "Cris"), queues.getList<String>("pharmacist.patientName"))
        assertEquals(listOf(eva), queues.getList<String>("stockCheck.id"))
        assertEquals(emptyList(), queues.getList<String>("packaging.id"))
        assertEquals(emptyList(), queues.getList<String>("pickup.id"))
    }

    @Test
    fun `the patient's page counts the people ahead, and only where they wait for staff`() {
        val ana = waitingForPharmacist("ibuprofen", name = "Ana")
        val bia = waitingForPharmacist("ibuprofen", name = "Bia")

        assertEquals(0, get("/api/prescriptions/$ana").jsonPath().getInt("peopleAhead"))
        assertEquals(1, get("/api/prescriptions/$bia").jsonPath().getInt("peopleAhead"))
        post("/api/staff/prescriptions/$ana/approve")
        assertEquals(0, get("/api/prescriptions/$bia").jsonPath().getInt("peopleAhead"), "Ana is in the packing queue, not Bia's")
        assertEquals(false, get("/api/prescriptions/$bia").jsonPath().getBoolean("delayed"))
    }

    // What the API says when it cannot help

    @Test
    fun `unknown routes and wrong methods are told in the API's own words`() {
        val unknown = get("/api/no-such-thing")
        assertEquals(404, unknown.statusCode)
        assertEquals(mapOf("error" to "Not found"), unknown.jsonPath().getMap<String, Any>(""))

        val wrongMethod = post("/api/board")
        assertEquals(405, wrongMethod.statusCode)
        assertEquals(mapOf("error" to "Method not allowed"), wrongMethod.jsonPath().getMap<String, Any>(""))

        val notJson = given().contentType(ContentType.TEXT).body("hello").post("/api/prescriptions")
        assertEquals(415, notJson.statusCode)
        assertEquals("Unsupported media type: this API takes JSON", notJson.jsonPath().getString("error"))
        assertEquals(setOf("error"), notJson.jsonPath().getMap<String, Any>("").keys)
    }

    @Test
    fun `a request body that is not JSON is a 400 in the API's shape, without the parser's internals`() {
        val response = checkIn("not json")

        assertEquals(400, response.statusCode)
        assertEquals(setOf("error"), response.jsonPath().getMap<String, Any>("").keys)
        assertEquals("The request could not be read", response.jsonPath().getString("error"))
    }
}
