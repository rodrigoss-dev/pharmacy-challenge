package pharmacy.infra.metrics

import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton
import java.time.Duration

/** Quarkus registers every MeterFilter bean with the Micrometer registry. */
@Singleton
class MeterConfiguration {
    /**
     * Latency buckets for the API's requests (`http_server_requests_seconds_bucket`), so Grafana can draw percentiles.
     * Without them Quarkus only exports a count, a sum and a maximum. The 1s bucket is the "slow request" line of
     * RequestLogging.
     */
    @Produces
    @Singleton
    fun httpLatencyBuckets(): MeterFilter = object : MeterFilter {
        override fun configure(id: Meter.Id, config: DistributionStatisticConfig): DistributionStatisticConfig =
            if (id.name == "http.server.requests") {
                DistributionStatisticConfig.builder().serviceLevelObjectives(*BUCKETS).build().merge(config)
            } else {
                config
            }
    }

    private companion object {
        val BUCKETS = listOf(10L, 25, 50, 100, 250, 500, 1_000, 2_500, 5_000)
            .map { Duration.ofMillis(it).toNanos().toDouble() }
            .toDoubleArray()
    }
}
