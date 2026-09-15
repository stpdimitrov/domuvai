rootProject.name = "domuvai"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Lets Gradle provision a JDK 21 toolchain if a machine lacks one.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// Pure-Kotlin shared libraries (no Spring) — the ADR-001 layer.
include(":kernel")
include(":law")
include(":charges")

// The Spring Boot deployable — a Spring Modulith modular monolith (ADR-003, ADR-010).
include(":app")
