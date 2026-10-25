package pharmacy.infra.persistence

import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import javax.sql.DataSource

@Singleton
class JooqConfiguration {
    /**
     * jOOQ on top of Quarkus' connection pool. Connections taken inside a transaction are enlisted in it, so every query
     * of a use case runs on the same connection and commits (or rolls back) together.
     */
    @Produces
    @Singleton
    fun dsl(dataSource: DataSource): DSLContext = DSL.using(dataSource, SQLDialect.POSTGRES)
}
