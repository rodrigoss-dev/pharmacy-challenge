package pharmacy.infra.government

import com.fasterxml.jackson.core.JacksonException
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.event.Observes
import jakarta.inject.Singleton
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import pharmacy.domain.GovernmentCommit
import pharmacy.domain.GovernmentPrescription
import pharmacy.domain.GovernmentSystemUnavailableException
import pharmacy.domain.port.GovernmentPrescriptions
import pharmacy.infra.metrics.GovernmentMetrics
import java.io.IOException
import java.net.ConnectException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import kotlin.text.Charsets.UTF_8

/** How the government system answered a commit. */
sealed interface CommitOutcome {
    data object Accepted : CommitOutcome

    /** Refused for good (the government said no): sending it again cannot help. */
    data class Rejected(val reason: String) : CommitOutcome

    /** Unreachable, too slow or failing: worth trying again later. */
    data class Failed(val reason: String) : CommitOutcome
}

/**
 * The government prescription system over HTTP (a mock of it lives in gov-api/). No authentication: out of scope. Every
 * call has a timeout, so a government system that hangs cannot hold a thread for long.
 */
@Singleton
class GovernmentApiClient(
    @ConfigProperty(name = "pharmacy.government-api.url") url: String,
    @ConfigProperty(name = "pharmacy.government-api.timeout") private val timeout: Duration,
    private val metrics: GovernmentMetrics,
) : GovernmentPrescriptions {
    private val log = Logger.getLogger(javaClass)
    private val baseUrl = url.trimEnd('/')
    private val http = HttpClient.newBuilder().connectTimeout(timeout).build()

    fun start(@Observes event: StartupEvent) {
        log.infof("Government prescription system at %s (timeout %ss)", baseUrl, timeout.toSeconds())
    }

    /** Done while the patient waits at the check-in, so it is not retried: they can try again, or type their medication. */
    override fun find(number: String): GovernmentPrescription? {
        val started = System.nanoTime()
        var call = GovernmentMetrics.Call.LOOKUP_FAILED // unless it ends below with an answer
        try {
            val found = lookUp(number)
            call = if (found != null) GovernmentMetrics.Call.LOOKUP_FOUND else GovernmentMetrics.Call.LOOKUP_NOT_FOUND
            return found
        } finally {
            metrics.record(call, Duration.ofNanos(System.nanoTime() - started))
        }
    }

    private fun lookUp(number: String): GovernmentPrescription? {
        // Before the question goes out: what the government accepted from this pharmacy before this moment is in its answer.
        val askedAt = Instant.now()
        val request = request("/prescriptions/${number.asPathSegment()}").GET().build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            log.warnf("Government system unreachable while looking up %s: %s", number, e.summary())
            throw GovernmentSystemUnavailableException()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw GovernmentSystemUnavailableException()
        }
        return when (response.statusCode()) {
            200 -> try {
                GovernmentMessages.decodePrescription(response.body(), askedAt)
            } catch (e: JacksonException) {
                log.warnf("Government system sent a prescription we cannot read for %s: %s", number, e.originalMessage)
                throw GovernmentSystemUnavailableException()
            }
            404 -> null
            else -> {
                log.warnf("Government system answered HTTP %d while looking up %s", response.statusCode(), number)
                throw GovernmentSystemUnavailableException()
            }
        }
    }

    /** Tells the government what was used. Repeating a commit is safe: the prescription id identifies it. */
    fun commit(commit: GovernmentCommit): CommitOutcome {
        val started = System.nanoTime()
        val outcome = send(commit)
        metrics.record(
            when (outcome) {
                CommitOutcome.Accepted -> GovernmentMetrics.Call.COMMIT_ACCEPTED
                is CommitOutcome.Rejected -> GovernmentMetrics.Call.COMMIT_REFUSED
                is CommitOutcome.Failed -> GovernmentMetrics.Call.COMMIT_FAILED
            },
            Duration.ofNanos(System.nanoTime() - started),
        )
        return outcome
    }

    private fun send(commit: GovernmentCommit): CommitOutcome {
        val request = request("/prescriptions/${commit.governmentNumber.asPathSegment()}/commits")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(GovernmentMessages.encodeCommit(commit)))
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            return CommitOutcome.Failed(e.summary())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return CommitOutcome.Failed("interrupted")
        }
        val status = response.statusCode()
        return when {
            status in 200..299 -> CommitOutcome.Accepted
            // The government is busy or broken, not unhappy with the commit.
            status == 408 || status == 429 || status >= 500 -> CommitOutcome.Failed("HTTP $status")
            status in 400..499 -> CommitOutcome.Rejected("HTTP $status: ${GovernmentMessages.decodeError(response.body()) ?: "no reason given"}")
            else -> CommitOutcome.Failed("unexpected HTTP $status")
        }
    }

    private fun request(path: String) = HttpRequest.newBuilder(URI.create(baseUrl + path))
        .timeout(timeout)
        .header("Accept", "application/json")

    /** Numbers are typed by the patient: never let one change the path. */
    private fun String.asPathSegment() = URLEncoder.encode(this, UTF_8).replace("+", "%20")

    private fun Throwable.summary() = when (this) {
        // The JDK gives no message at all when nothing listens on the port.
        is ConnectException -> "could not connect (is the government system running?)"
        else -> "${javaClass.simpleName}: ${message ?: "no details"}"
    }
}
