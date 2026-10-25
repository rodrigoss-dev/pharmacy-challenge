package pharmacy.infra

import org.flywaydb.core.Flyway

/** Drops everything and migrates again: empty tables, starting stock, ticket numbers from 1. */
fun Flyway.reset() {
    clean()
    migrate()
}
