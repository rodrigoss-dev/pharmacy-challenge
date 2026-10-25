// Quarkus: everything that talks to the outside world. The REST API and SSE, RabbitMQ consumers and the outbox relay,
// jOOQ/PostgreSQL persistence. Inbound adapters (REST, RabbitMQ) call the domain's use cases; outbound adapters
// implement its ports. The web app (../web) is built and deployed on its own.
plugins {
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.quarkus)
    alias(libs.plugins.jooq.codegen)
}

dependencies {
    implementation(project(":domain"))

    implementation(enforcedPlatform(libs.quarkus.bom))
    implementation(libs.quarkus.arc)
    implementation(libs.quarkus.kotlin)
    implementation(libs.quarkus.rest.jackson)
    implementation(libs.jackson.kotlin)
    implementation(libs.quarkus.jdbc.postgresql)
    implementation(libs.quarkus.flyway)
    implementation(libs.flyway.postgresql)
    implementation(libs.quarkus.narayana.jta)
    implementation(libs.quarkus.smallrye.health)
    implementation(libs.quarkus.micrometer.prometheus)
    implementation(libs.quarkus.logging.json)
    implementation(libs.jooq)
    implementation(libs.amqp.client)

    jooqCodegen(libs.jooq.meta.extensions)

    testImplementation(libs.quarkus.junit)
    testImplementation(libs.rest.assured)
}

kotlin {
    compilerOptions {
        javaParameters = true
    }
}

// Dev mode starts in the repository root, so the log file (logs/pharmacy.log) lands where the observability stack
// in docker-compose.yml reads it, whether the backend runs from Gradle or from the jar.
tasks.named<io.quarkus.gradle.tasks.QuarkusDev>("quarkusDev") {
    workingDirectory = rootProject.layout.projectDirectory.asFile
}

// CDI proxies and Quarkus tests subclass these at runtime; Kotlin classes are final by default.
allOpen {
    annotation("jakarta.ws.rs.Path")
    annotation("jakarta.enterprise.context.ApplicationScoped")
    annotation("io.quarkus.test.junit.QuarkusTest")
}

// jOOQ classes are generated from the Flyway migrations themselves, so the build needs no running database.
val migrations = layout.projectDirectory.dir("src/main/resources/db/migration")
val generatedJooq = layout.buildDirectory.dir("generated-sources/jooq")

jooq {
    configuration {
        generator {
            name = "org.jooq.codegen.KotlinGenerator"
            database {
                name = "org.jooq.meta.extensions.ddl.DDLDatabase"
                properties {
                    property { key = "scripts"; value = migrations.asFile.path }
                    property { key = "sort"; value = "flyway" }
                    property { key = "defaultNameCase"; value = "lower" }
                    // Seed data is wrapped in [jooq ignore start/stop] comments: only the schema matters here.
                    property { key = "parseIgnoreComments"; value = "true" }
                }
                // The DDL is simulated in H2, which turns jsonb into json.
                forcedTypes {
                    forcedType { name = "JSONB"; includeTypes = "JSON" }
                }
            }
            generate {
                isKotlinNotNullRecordAttributes = true
            }
            target {
                packageName = "pharmacy.infra.persistence.jooq"
                directory = generatedJooq.get().asFile.path
            }
        }
    }
}

tasks.named("jooqCodegen") {
    inputs.dir(migrations)
    outputs.dir(generatedJooq)
}

kotlin.sourceSets.main {
    kotlin.srcDir(tasks.named("jooqCodegen"))
}
