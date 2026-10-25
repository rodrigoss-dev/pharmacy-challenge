package pharmacy.infra.api

import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.container.ContainerResponseContext
import org.jboss.logging.Logger
import org.jboss.resteasy.reactive.server.ServerRequestFilter
import org.jboss.resteasy.reactive.server.ServerResponseFilter
import java.time.Duration

/**
 * One line per API request: `POST /api/staff/prescriptions/.../approve -> 409 in 12 ms: Prescription #1 is already waiting to be packed...`.
 *
 * Changes (anything but GET) at INFO, client errors at INFO, server errors and slow requests at WARN. The polling of the
 * screens and the staff console (a GET every few seconds each) only at TRACE.
 */
class RequestLogging {
    private val log = Logger.getLogger(javaClass)

    @ServerRequestFilter(preMatching = true)
    fun started(request: ContainerRequestContext) {
        request.setProperty(STARTED, System.nanoTime())
    }

    @ServerResponseFilter
    fun logRequest(request: ContainerRequestContext, response: ContainerResponseContext) {
        val path = request.uriInfo.path
        if (!path.startsWith("/api") && path != "/health") return
        val took = (request.getProperty(STARTED) as? Long)?.let { Duration.ofNanos(System.nanoTime() - it) }
        val status = response.status
        val error = request.getProperty(ERROR) as? String
        val cause = request.getProperty(CAUSE) as? String
        val line = "${request.method} $path -> $status${took?.let { " in ${it.toMillis()} ms" }.orEmpty()}" +
            error?.let { ": $it" }.orEmpty() + cause?.let { " ($it)" }.orEmpty()
        when {
            status >= 500 -> log.warn(line)
            took != null && took > SLOW -> log.warn("Slow request: $line")
            status >= 400 || request.method != "GET" -> log.info(line)
            else -> log.trace(line)
        }
    }

    companion object {
        private const val STARTED = "pharmacy.request.started"

        /** The message the client got, set by [ErrorMapping]. */
        const val ERROR = "pharmacy.request.error"

        /** Why, when the client is not told (e.g. the database error behind a 503). */
        const val CAUSE = "pharmacy.request.cause"

        private val SLOW: Duration = Duration.ofSeconds(1)
    }
}
