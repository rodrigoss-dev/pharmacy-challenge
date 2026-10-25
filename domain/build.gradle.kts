// Entities, business rules, use cases and the ports they need. Plain Kotlin: no framework, database or broker on the
// classpath. Only the infra module calls in, from a REST endpoint or a RabbitMQ consumer.

// The business rules are specified as Gherkin scenarios (src/test/resources/pharmacy/domain), run by Cucumber.
dependencies {
    "testImplementation"(platform(libs.cucumber.bom))
    "testImplementation"(platform(libs.junit.bom))
    "testImplementation"(libs.cucumber.java)
    "testImplementation"(libs.cucumber.junit.platform.engine)
    "testImplementation"(libs.junit.platform.suite)
}
