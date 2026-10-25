package pharmacy.infra.logging

import org.jboss.logmanager.ExtLogRecord
import java.util.logging.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogTypeTest {
    @Test
    fun `what the domain logs is business, everything else is system`() {
        assertEquals(LogType.BUSINESS, LogType.of("pharmacy.domain.usecase.ReserveStock"))
        assertEquals(LogType.BUSINESS, LogType.of("pharmacy.domain"))

        assertEquals(LogType.SYSTEM, LogType.of("pharmacy.infra.api.RequestLogging"))
        assertEquals(LogType.SYSTEM, LogType.of("pharmacy.infra.messaging.OutboxRelay"))
        assertEquals(LogType.SYSTEM, LogType.of("pharmacy.domainish.Other"))
        assertEquals(LogType.SYSTEM, LogType.of("io.quarkus"))
        assertEquals(LogType.SYSTEM, LogType.of(null))
    }

    @Test
    fun `the filter marks the record and lets it through`() {
        fun marked(logger: String): String? {
            val record = ExtLogRecord(Level.INFO, "a message", LogTypeTest::class.java.name).apply { loggerName = logger }
            assertTrue(LogTypeFilter().isLoggable(record))
            return record.getMdc(LogType.FIELD)
        }

        assertEquals("business", marked("pharmacy.domain.usecase.SubmitPrescription"))
        assertEquals("system", marked("pharmacy.infra.persistence.Transactions"))
    }
}
