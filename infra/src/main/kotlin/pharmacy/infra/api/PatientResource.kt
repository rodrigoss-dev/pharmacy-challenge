package pharmacy.infra.api

import com.fasterxml.jackson.databind.ObjectMapper
import io.smallrye.mutiny.Multi
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.sse.OutboundSseEvent
import jakarta.ws.rs.sse.Sse
import org.jboss.resteasy.reactive.RestPath
import org.jboss.resteasy.reactive.RestResponse
import pharmacy.domain.CheckInRacedException
import pharmacy.domain.NotFoundException
import pharmacy.domain.usecase.LookUpGovernmentPrescription
import pharmacy.domain.usecase.MedicationCatalog
import pharmacy.domain.usecase.SubmitPrescription
import pharmacy.domain.usecase.TrackPrescription
import pharmacy.domain.usecase.WaitingRoomBoard
import pharmacy.infra.persistence.Transactions
import pharmacy.infra.persistence.isUniqueViolation
import java.time.Duration

// Methods returning plain values run on worker threads (the use cases block on JDBC); the SSE streams run their
// queries on a pool of their own (see LiveUpdates). Database work goes through Transactions: one transaction per
// change, retries for lost races, and a 503 when the database cannot be reached.
@Path("/api")
class PatientResource(
    private val medicationCatalog: MedicationCatalog,
    private val lookUpGovernmentPrescription: LookUpGovernmentPrescription,
    private val submitPrescription: SubmitPrescription,
    private val trackPrescription: TrackPrescription,
    private val waitingRoomBoard: WaitingRoomBoard,
    private val liveUpdates: LiveUpdates,
    private val transactions: Transactions,
    private val json: ObjectMapper,
) {
    @GET
    @Path("/medications")
    fun medications(): List<MedicationResponse> =
        transactions.read("listing medications") { medicationCatalog.list() }.map { it.toResponse() }

    /**
     * What a government prescription number lets the patient collect, as the government system has it right now. Not
     * in a transaction: it asks another system, and touches no database.
     */
    @GET
    @Path("/government-prescriptions/{number}")
    fun governmentPrescription(@RestPath number: String): GovernmentPrescriptionResponse =
        lookUpGovernmentPrescription(number).toResponse()

    @POST
    @Path("/prescriptions")
    fun checkIn(request: CheckInRequest?): RestResponse<CheckInResponse> {
        val body = request ?: CheckInRequest()
        val id = body.id?.toPrescriptionId()
        // Asked again here, not trusted from the lookup the patient saw: the government system is the one that decides.
        // And before the transaction opens: waiting for another system must not hold a database connection, nor the
        // lock on the ticket counter that every check-in queues on. Not for a check-in that went through already (its
        // response got lost, and this is the retry): the ticket comes back as it is, even if the government system is
        // down by now.
        val government = body.governmentNumber
            ?.takeUnless { id != null && transactions.read("check-in $id: done already?") { submitPrescription.isCheckedIn(id) } }
            ?.let { lookUpGovernmentPrescription(it) }
        val command = body.toCommand(government)
        // The same check-in twice at once (a second tap while the first is in flight): the second finds out once the first
        // commits, at the ticket counter or, failing that, on the primary key. Running it again finds the first and
        // returns its ticket.
        val prescription = transactions.transaction(
            "check-in",
            retryIf = { command.id != null && (it is CheckInRacedException || it.isUniqueViolation("prescriptions_pkey")) },
        ) { submitPrescription(command) }
        return RestResponse.ResponseBuilder
            .create(RestResponse.Status.CREATED, CheckInResponse(prescription.id.toString(), prescription.ticketNumber))
            .header(HttpHeaders.LOCATION, "/api/prescriptions/${prescription.id}")
            .build()
    }

    @GET
    @Path("/prescriptions/{id}")
    fun track(@RestPath id: String): TrackingResponse {
        val prescriptionId = id.toPrescriptionId()
        return transactions.read("tracking $prescriptionId") { trackPrescription(prescriptionId) }.toResponse()
    }

    /** Server-Sent Events: the patient's phone keeps one connection open and gets every change as it happens. */
    @GET
    @Path("/prescriptions/{id}/live")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    fun liveTracking(@RestPath id: String, @Context sse: Sse): Multi<OutboundSseEvent> {
        val prescriptionId = id.toPrescriptionId()
        val updates = liveUpdates.watch("tracking $prescriptionId") {
            transactions.read("live tracking of $prescriptionId") { trackPrescription(prescriptionId) }.toResponse()
        }
        return sse.stream("status", updates)
    }

    @GET
    @Path("/board")
    fun board(): BoardResponse = transactions.read("waiting room board") { waitingRoomBoard() }.toResponse()

    @GET
    @Path("/board/live")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    fun liveBoard(@Context sse: Sse): Multi<OutboundSseEvent> =
        sse.stream("board", liveUpdates.watch("board") { transactions.read("live board") { waitingRoomBoard() }.toResponse() })

    /**
     * Sends each update as a named event with a JSON payload, with heartbeats so proxies do not close an idle connection.
     * When the query cannot run, an `unavailable` event says so (the screen keeps what it has and says it is catching
     * up); the next update replaces it. A prescription that disappears ends the stream with a `not-found` event.
     */
    private fun <T : Any> Sse.stream(event: String, updates: Multi<LiveUpdate<T>>): Multi<OutboundSseEvent> {
        fun named(name: String, data: Any) = newEventBuilder().name(name).data(json.writeValueAsString(data)).build()

        val end = newEventBuilder().comment("end").build()
        val events = updates
            .map {
                when (it) {
                    is LiveUpdate.Value -> named(event, it.value)
                    LiveUpdate.Unavailable -> named("unavailable", ErrorResponse(UNAVAILABLE_MESSAGE))
                }
            }
            .onFailure(NotFoundException::class.java).recoverWithItem { e -> named("not-found", ErrorResponse(e.message ?: "Not found")) }
            .onCompletion().continueWith(end)
        val heartbeats = Multi.createFrom().ticks().startingAfter(HEARTBEAT_EVERY).every(HEARTBEAT_EVERY)
            .map { newEventBuilder().comment("heartbeat").build() }
        return Multi.createBy().merging().streams(events, heartbeats).select().first { it !== end }
    }

    private companion object {
        val HEARTBEAT_EVERY: Duration = Duration.ofSeconds(15)
        const val UNAVAILABLE_MESSAGE = "We can't reach the pharmacy system right now. This page catches up by itself."
    }
}
