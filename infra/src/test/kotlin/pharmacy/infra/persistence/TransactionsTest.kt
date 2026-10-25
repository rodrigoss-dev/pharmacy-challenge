package pharmacy.infra.persistence

import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import pharmacy.domain.ValidationException
import java.sql.SQLException
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Against the real database: the failures are simulated by the blocks, the transactions and the probe are real. */
@QuarkusTest
class TransactionsTest {
    @Inject lateinit var transactions: Transactions
    @Inject lateinit var health: DatabaseHealth

    @Test
    fun `a lost race is retried and nobody notices`() {
        var attempts = 0
        val result = transactions.transaction("racing") {
            if (++attempts == 1) throw SQLException("deadlock detected", "40P01")
            "done"
        }
        assertEquals("done", result)
        assertEquals(2, attempts)
    }

    @Test
    fun `an unreachable database is a 503, and opens the circuit until a probe finds it back`() {
        val failure = assertFailsWith<DatabaseUnavailableException> {
            transactions.read("unlucky") { throw SQLException("Connection to localhost:5432 refused", "08001") }
        }
        assertEquals(DatabaseFailure.UNAVAILABLE, failure.failure)
        assertFalse(health.isAvailable)
        // While it is open, work fails fast, without trying the database.
        assertFailsWith<DatabaseUnavailableException> { transactions.read("during the outage") { error("must not run") } }

        // The database is actually there: the background probe finds it, and work carries on.
        assertTrue(health.awaitAvailable(Duration.ofSeconds(10)))
        assertEquals(1, transactions.read("after the outage") { 1 })
    }

    @Test
    fun `business rules and bugs pass through untouched, without retries`() {
        var attempts = 0
        assertFailsWith<ValidationException> { transactions.transaction("refused") { attempts++; throw ValidationException("no") } }
        assertFailsWith<IllegalStateException> { transactions.transaction("buggy") { attempts++; error("bug") } }
        assertEquals(2, attempts)
        assertTrue(health.isAvailable)
    }
}
