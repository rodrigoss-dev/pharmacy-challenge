package pharmacy.infra.persistence

import io.quarkus.narayana.jta.QuarkusTransactionException
import org.jooq.exception.DataAccessException
import java.net.ConnectException
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DatabaseFailureTest {
    private fun sql(state: String?, message: String = "boom") = SQLException(message, state)

    @Test
    fun `unreachable, shutting down or out of connections`() {
        listOf("08001", "08006", "57P01", "57P03", "53300", "25006").forEach {
            assertEquals(DatabaseFailure.UNAVAILABLE, DatabaseFailure.of(sql(it)), it)
        }
        assertEquals(DatabaseFailure.UNAVAILABLE, DatabaseFailure.of(sql(null, "Sorry, acquisition timeout!")))
        assertEquals(DatabaseFailure.UNAVAILABLE, DatabaseFailure.of(RuntimeException(ConnectException("Connection refused"))))
    }

    @Test
    fun `lost races and timeouts`() {
        listOf("40001", "40P01", "55P03").forEach { assertEquals(DatabaseFailure.CONTENTION, DatabaseFailure.of(sql(it)), it) }
        assertEquals(DatabaseFailure.TIMEOUT, DatabaseFailure.of(sql("57014")))
    }

    @Test
    fun `found however deep jOOQ and the transaction manager wrap it`() {
        val wrapped = QuarkusTransactionException(DataAccessException("SQL [update ...]", sql("40P01", "deadlock detected")))
        assertEquals(DatabaseFailure.CONTENTION, DatabaseFailure.of(wrapped))
        assertEquals("40P01 deadlock detected", wrapped.databaseSummary())
    }

    @Test
    fun `bugs and bad data are not database failures`() {
        assertNull(DatabaseFailure.of(sql("23505", "duplicate key value violates unique constraint \"prescriptions_pkey\"")))
        assertNull(DatabaseFailure.of(sql("42P01", "relation \"nope\" does not exist")))
        assertNull(DatabaseFailure.of(IllegalStateException("bug")))
    }

    @Test
    fun `unique violations name their constraint`() {
        val duplicate = DataAccessException("SQL [insert ...]", sql("23505", "ERROR: duplicate key value violates unique constraint \"prescriptions_pkey\""))
        assertTrue(duplicate.isUniqueViolation("prescriptions_pkey"))
        assertFalse(duplicate.isUniqueViolation("prescriptions_ticket_number_key"))
    }
}
