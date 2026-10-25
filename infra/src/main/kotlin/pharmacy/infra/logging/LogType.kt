package pharmacy.infra.logging

import io.quarkus.logging.LoggingFilter
import org.jboss.logmanager.ExtLogRecord
import java.util.logging.Filter
import java.util.logging.LogRecord

/**
 * What a log line is about. Business lines are the domain's events (check-ins, status changes, refusals, stock);
 * system lines are everything the adapters say (requests, database, RabbitMQ, startup).
 *
 * The domain logs under `pharmacy.domain` (see DomainLog), so its package is what makes a line a business line.
 */
enum class LogType {
    BUSINESS,
    SYSTEM,
    ;

    /** The value in the log file and the Loki label: `business`, `system`. */
    val label: String get() = name.lowercase()

    companion object {
        const val FIELD = "log_type"
        private const val DOMAIN = "pharmacy.domain"

        fun of(loggerName: String?): LogType =
            if (loggerName != null && (loggerName == DOMAIN || loggerName.startsWith("$DOMAIN."))) BUSINESS else SYSTEM
    }
}

/**
 * Stamps every line the log file receives with its [LogType], as the `log_type` entry of the record's MDC, which the
 * JSON formatter writes under `mdc`. Alloy turns it into a Loki label (observability/alloy/config.alloy). Attached to
 * the file handler in application.properties.
 */
@LoggingFilter(name = "log-type")
class LogTypeFilter : Filter {
    override fun isLoggable(record: LogRecord): Boolean {
        (record as? ExtLogRecord)?.putMdc(LogType.FIELD, LogType.of(record.loggerName).label)
        return true
    }
}
