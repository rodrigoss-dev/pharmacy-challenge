package pharmacy.infra.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.inject.Singleton
import java.time.Duration

/**
 * How the calls to the government prescription system go, as Prometheus metrics. Recorded by the HTTP client, one call
 * at a time; the commits waiting in the outbox come from the database instead (see [SystemMetrics]).
 *
 * - `pharmacy_government_requests_total{operation,outcome}`: every call, by what it was and how it ended.
 * - `pharmacy_government_request_seconds{operation}`: how long calls take, with buckets for percentiles. A call that
 *   times out shows as the timeout, so a government system that hangs is visible here before it is visible anywhere else.
 * - `pharmacy_government_up`: whether the last call was answered, like `pharmacy_broker_up` for RabbitMQ. It has no
 *   value until the first call (nothing asks the government system while nobody looks anything up or collects).
 */
@Singleton
class GovernmentMetrics(registry: MeterRegistry) {

    /** What a call was, and how it ended. */
    enum class Call(val operation: String, val outcome: String, val answered: Boolean) {
        LOOKUP_FOUND("lookup", "found", true),
        LOOKUP_NOT_FOUND("lookup", "not_found", true),

        /** Unreachable, too slow, or an answer that is not one (an error, or something unreadable). */
        LOOKUP_FAILED("lookup", "failed", false),
        COMMIT_ACCEPTED("commit", "accepted", true),

        /** The government said no, for good. It answered, so it is up. */
        COMMIT_REFUSED("commit", "refused", true),
        COMMIT_FAILED("commit", "failed", false),
    }

    private val calls = Call.entries.associateWith {
        Counter.builder("pharmacy.government.requests")
            .description("Calls to the government prescription system, by operation and outcome")
            .tag("operation", it.operation)
            .tag("outcome", it.outcome)
            .register(registry)
    }
    private val latency = Call.entries.map { it.operation }.distinct().associateWith {
        Timer.builder("pharmacy.government.request")
            .description("How long calls to the government prescription system take")
            .tag("operation", it)
            .serviceLevelObjectives(*BUCKETS)
            .register(registry)
    }

    @Volatile
    private var lastCallAnswered: Boolean? = null

    init {
        Gauge.builder("pharmacy.government.up") { lastCallAnswered?.let { if (it) 1.0 else 0.0 } ?: Double.NaN }
            .description("1 if the last call to the government system was answered, 0 if it failed; no value before the first call")
            .register(registry)
    }

    fun record(call: Call, took: Duration) {
        calls.getValue(call).increment()
        latency.getValue(call.operation).record(took)
        lastCallAnswered = call.answered
    }

    private companion object {
        /** Up to the client's timeout (5s): the last bucket is "gave up". */
        val BUCKETS = listOf(10L, 25, 50, 100, 250, 500, 1_000, 2_500, 5_000).map { Duration.ofMillis(it) }.toTypedArray()
    }
}
