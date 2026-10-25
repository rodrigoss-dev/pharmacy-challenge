package pharmacy.infra

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.event.Observes
import jakarta.inject.Singleton
import org.eclipse.microprofile.config.ConfigProvider
import org.jboss.logging.Logger

/** At startup, one line on how this instance reaches its database and how long it waits for it. */
@Singleton
class StartupReport {
    private val log = Logger.getLogger(javaClass)

    fun report(@Observes event: StartupEvent) {
        val config = ConfigProvider.getConfig()
        fun value(key: String) = config.getOptionalValue(key, String::class.java).orElse("default")
        log.infof(
            "Database at %s: pool of up to %s connections (waits %s for one); connect timeout %ss, socket timeout %ss; server options: %s",
            value("quarkus.datasource.jdbc.url").replace(Regex("(?i)password=[^&;]*"), "password=***"),
            value("quarkus.datasource.jdbc.max-size"),
            value("quarkus.datasource.jdbc.acquisition-timeout"),
            value("quarkus.datasource.jdbc.additional-jdbc-properties.connectTimeout"),
            value("quarkus.datasource.jdbc.additional-jdbc-properties.socketTimeout"),
            value("quarkus.datasource.jdbc.additional-jdbc-properties.options"),
        )
    }
}
