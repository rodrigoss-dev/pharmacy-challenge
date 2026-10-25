package pharmacy.infra.persistence

import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

/** The database as [DatabaseHealth] asks about it: there, or (while [down]) refusing connections. */
class ToggleDataSource(private val real: DataSource) : DataSource by real {
    @Volatile var down = false

    override fun getConnection(): Connection {
        if (down) throw SQLException("Connection to localhost:5432 refused", "08001")
        return real.connection
    }
}
