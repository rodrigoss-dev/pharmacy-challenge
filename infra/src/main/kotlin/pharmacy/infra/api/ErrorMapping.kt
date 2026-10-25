package pharmacy.infra.api

import com.fasterxml.jackson.databind.exc.MismatchedInputException
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.container.ContainerRequestContext
import jakarta.ws.rs.core.HttpHeaders
import org.jboss.logging.Logger
import org.jboss.resteasy.reactive.RestResponse
import org.jboss.resteasy.reactive.RestResponse.Status
import org.jboss.resteasy.reactive.server.ServerExceptionMapper
import pharmacy.domain.CheckInIdReusedException
import pharmacy.domain.ConcurrentUpdateException
import pharmacy.domain.DomainException
import pharmacy.domain.GovernmentSystemUnavailableException
import pharmacy.domain.IllegalTransitionException
import pharmacy.domain.NotFoundException
import pharmacy.domain.ValidationException
import pharmacy.infra.persistence.DatabaseFailure
import pharmacy.infra.persistence.DatabaseUnavailableException
import pharmacy.infra.persistence.databaseSummary

/**
 * Every error answers with `{"error": "..."}`, a message the web app can show as it is. The request log line
 * ([RequestLogging]) carries the message too; unexpected errors are also logged here, with the stack trace.
 */
class ErrorMapping {
    private val log = Logger.getLogger(javaClass)

    @ServerExceptionMapper
    fun domain(e: DomainException, request: ContainerRequestContext): RestResponse<ErrorResponse> = error(
        request,
        when (e) {
            is ValidationException -> Status.BAD_REQUEST
            is NotFoundException -> Status.NOT_FOUND
            is IllegalTransitionException, is ConcurrentUpdateException, is CheckInIdReusedException -> Status.CONFLICT
            // Another system failed us, not ours: a 503 would be retried by the web app as if the database were down.
            is GovernmentSystemUnavailableException -> Status.BAD_GATEWAY
        },
        e.message!!,
    )

    /**
     * The database is unreachable, overloaded or too slow: try again shortly. Already logged where it was detected
     * (DatabaseHealth, Transactions); the web app retries check-ins by itself.
     */
    @ServerExceptionMapper
    fun databaseUnavailable(e: DatabaseUnavailableException, request: ContainerRequestContext): RestResponse<ErrorResponse> {
        request.setProperty(RequestLogging.CAUSE, e.cause?.databaseSummary())
        return unavailable(request)
    }

    /**
     * JSON that is well formed but not what the endpoint takes: a null where a value is needed, a word where a number
     * goes. Quarkus has an answer of its own for this, in a shape no other error of this API has.
     */
    @ServerExceptionMapper
    fun unreadable(e: MismatchedInputException, request: ContainerRequestContext): RestResponse<ErrorResponse> {
        val where = e.path.fold("") { path, step ->
            when {
                step.fieldName != null -> if (path.isEmpty()) step.fieldName else "$path.${step.fieldName}"
                else -> "$path[${step.index}]"
            }
        }
        return error(
            request,
            Status.BAD_REQUEST,
            if (where.isEmpty()) "The request could not be read" else "The request has a missing or invalid value for '$where'",
        )
    }

    /**
     * Malformed ids or JSON, unsupported methods... keep the status Quarkus chose. Its own words for a route it could not
     * match name its internals, so those get ours; the rest of the messages were written by this API.
     */
    @ServerExceptionMapper
    fun web(e: WebApplicationException, request: ContainerRequestContext): RestResponse<ErrorResponse> {
        val status = Status.fromStatusCode(e.response.status)
        val message = when (status) {
            Status.NOT_FOUND -> "Not found" // a prescription that does not exist is a domain NotFoundException, mapped above
            Status.METHOD_NOT_ALLOWED -> "Method not allowed"
            Status.NOT_ACCEPTABLE -> "Not acceptable"
            Status.UNSUPPORTED_MEDIA_TYPE -> "Unsupported media type: this API takes JSON"
            // JAX-RS words an error without a message of its own "HTTP 400 Bad Request": nothing a patient can use.
            else -> e.message?.takeUnless { it.startsWith("HTTP ") }
                ?: if (status == Status.BAD_REQUEST) "The request could not be read" else status.reasonPhrase
        }
        return error(request, status, message)
    }

    @ServerExceptionMapper
    fun unexpected(e: Exception, request: ContainerRequestContext): RestResponse<ErrorResponse> {
        // A database failure that did not go through Transactions is still the database's, not a bug.
        if (DatabaseFailure.of(e) != null) {
            log.warnf("Database failure on %s %s outside Transactions: %s", request.method, request.uriInfo.path, e.databaseSummary())
            request.setProperty(RequestLogging.CAUSE, e.databaseSummary())
            return unavailable(request)
        }
        log.errorf(e, "Unhandled error on %s %s", request.method, request.uriInfo.path)
        return error(request, Status.INTERNAL_SERVER_ERROR, "Something went wrong on our side. Please try again.")
    }

    private fun unavailable(request: ContainerRequestContext): RestResponse<ErrorResponse> {
        request.setProperty(RequestLogging.ERROR, UNAVAILABLE_MESSAGE)
        return RestResponse.ResponseBuilder.create(Status.SERVICE_UNAVAILABLE, ErrorResponse(UNAVAILABLE_MESSAGE))
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
            .build()
    }

    private fun error(request: ContainerRequestContext, status: Status, message: String): RestResponse<ErrorResponse> {
        request.setProperty(RequestLogging.ERROR, message)
        return RestResponse.ResponseBuilder.create(status, ErrorResponse(message))
            .header(HttpHeaders.CONTENT_TYPE, "application/json")
            .build()
    }

    private companion object {
        const val UNAVAILABLE_MESSAGE = "The pharmacy system is unavailable for a moment. Please try again in a few seconds."
        const val RETRY_AFTER_SECONDS = 3
    }
}
