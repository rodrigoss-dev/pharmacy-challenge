package pharmacy.infra.persistence

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.sql.SQLException
import java.sql.SQLNonTransientConnectionException
import java.sql.SQLRecoverableException
import java.sql.SQLTransientConnectionException

/**
 * What kind of trouble a failed database call ran into, read from the SQLState PostgreSQL reported (anywhere in the
 * cause chain: jOOQ and the transaction manager wrap it). Failures that are none of these are bugs or bad data, and are
 * not retried.
 */
enum class DatabaseFailure {
    /** The database cannot be reached, is shutting down or refuses connections; the pool may also have run dry. */
    UNAVAILABLE,

    /** Lost a race with another transaction (deadlock, serialization failure, lock wait timeout). Nothing was saved. */
    CONTENTION,

    /** Cancelled for running longer than `statement_timeout`. Nothing was saved. */
    TIMEOUT,
    ;

    companion object {
        fun of(e: Throwable): DatabaseFailure? = e.causes().firstNotNullOfOrNull(::classify)

        private fun classify(e: Throwable): DatabaseFailure? = when (e) {
            is SQLTransientConnectionException, is SQLNonTransientConnectionException, is SQLRecoverableException -> UNAVAILABLE
            is SQLException -> classify(e.sqlState, e.message)
            is ConnectException, is SocketTimeoutException -> UNAVAILABLE
            else -> null
        }

        private fun classify(sqlState: String?, message: String?): DatabaseFailure? = when {
            sqlState == null -> if (message.orEmpty().contains("acquisition timeout", ignoreCase = true)) UNAVAILABLE else null
            sqlState.startsWith("08") -> UNAVAILABLE // connection exception
            sqlState in setOf("57P01", "57P02", "57P03") -> UNAVAILABLE // admin shutdown, crash shutdown, cannot connect now
            sqlState.startsWith("53") -> UNAVAILABLE // insufficient resources: too many connections, disk full, out of memory
            sqlState == "25006" -> UNAVAILABLE // read-only transaction: connected to a standby, e.g. during a failover
            sqlState in setOf("40001", "40P01", "55P03") -> CONTENTION // serialization failure, deadlock, lock not available
            sqlState == "57014" -> TIMEOUT // query canceled
            else -> null
        }
    }
}

/** Unique constraint [constraint] was violated, e.g. `prescriptions_pkey`. */
fun Throwable.isUniqueViolation(constraint: String): Boolean =
    causes().any { it is SQLException && it.sqlState == "23505" && it.message.orEmpty().contains("\"$constraint\"") }

/** A one-line description for the logs, from the innermost SQL error: `08001 Connection to localhost:5432 refused...`. */
fun Throwable.databaseSummary(): String {
    val sql = causes().filterIsInstance<SQLException>().lastOrNull()
    val root = sql ?: causes().last()
    val state = (root as? SQLException)?.sqlState?.let { "$it " }.orEmpty()
    return state + (root.message?.lineSequence()?.first()?.take(300) ?: root.javaClass.simpleName)
}

private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }.take(20)
