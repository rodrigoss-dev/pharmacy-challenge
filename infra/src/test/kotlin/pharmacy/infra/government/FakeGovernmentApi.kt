package pharmacy.infra.government

import jakarta.enterprise.context.ApplicationScoped
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import org.jboss.resteasy.reactive.RestPath
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A stand-in for the government system, served by the app under test itself (the tests point
 * `pharmacy.government-api.url` here), so the real HTTP client talks to it. Speaks what gov-api/ speaks. Tests script
 * it by injecting this class, and read what it was sent.
 */
@Path("/fake-government")
@ApplicationScoped
class FakeGovernmentApi {
    data class Line(val productId: String, val quantity: Int, val maximum: Boolean)

    data class ReceivedCommit(val number: String, val commitId: String, val items: List<Pair<String, Int>>)

    data class CommitItemBody(val productId: String = "", val quantity: Int = 0)

    data class CommitBody(val commitId: String = "", val items: List<CommitItemBody> = emptyList())

    private val prescriptions = ConcurrentHashMap<String, List<Line>>()

    /** Every commit the app sent, in order. */
    val commits = CopyOnWriteArrayList<ReceivedCommit>()

    /** How many prescription lookups the app made. */
    @Volatile var lookups = 0

    /** While true, the system answers every call with a 503. */
    @Volatile var down = false

    /** What commits are answered with: 201 accepted, 409 refused for good, 503 try again... */
    @Volatile var commitStatus = 201

    /** While set, a lookup is answered with this body (and a 200) instead of the prescription. */
    @Volatile var lookupBody: String? = null

    /** How long a lookup takes to answer. */
    @Volatile var lookupDelayMs = 0L

    fun reset() {
        prescriptions.clear()
        commits.clear()
        lookups = 0
        down = false
        commitStatus = 201
        lookupBody = null
        lookupDelayMs = 0
    }

    fun prescribe(number: String, vararg lines: Line) {
        prescriptions[number.uppercase()] = lines.toList()
    }

    @GET
    @Path("/prescriptions/{number}")
    @Produces(MediaType.APPLICATION_JSON)
    fun find(@RestPath number: String): Response {
        lookups++
        if (lookupDelayMs > 0) Thread.sleep(lookupDelayMs)
        if (down) return Response.status(503).build()
        lookupBody?.let { return Response.ok(it).build() }
        // Like the real one: numbers are typed by hand, so case does not matter, and the number comes back as written.
        val lines = prescriptions[number.trim().uppercase()]
            ?: return Response.status(404).entity(mapOf("error" to "Prescription $number not found")).build()
        return Response.ok(mapOf("number" to number.trim().uppercase(), "items" to lines)).build()
    }

    @POST
    @Path("/prescriptions/{number}/commits")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    fun commit(@RestPath number: String, body: CommitBody): Response {
        if (down) return Response.status(503).build()
        commits += ReceivedCommit(number, body.commitId, body.items.map { it.productId to it.quantity })
        return Response.status(commitStatus).entity(mapOf("error" to "answering $commitStatus, as scripted")).build()
    }
}
