rootProject.name = "pharmacy"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// domain: the business rules and use cases, plain Kotlin.
// infra:  Quarkus. REST API, RabbitMQ and jOOQ/PostgreSQL; the only way into the domain.
// Dependencies point one way only: infra -> domain.
include("domain", "infra")
