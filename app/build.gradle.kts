plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)            // all-open for @Component/@Transactional classes
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

kotlin {
    jvmToolchain(21)
}

// Align the Spring-managed Kotlin stdlib with the Kotlin plugin we compile with.
extra["kotlin.version"] = libs.versions.kotlin.get()

dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:${libs.versions.springModulith.get()}")
    }
}

dependencies {
    // the pure ADR-001 domain: the charge engine and the types it exposes
    implementation(project(":charges"))
    implementation(project(":kernel"))
    implementation(project(":law"))

    // web + persistence
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // module boundaries + the durable outbox (event publication registry)
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("org.springframework.modulith:spring-modulith-starter-jdbc")

    // schema is owned by Flyway; PostgreSQL is the only supported database (ADR-006)
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    // implementation (not runtimeOnly): the jsonb converter references org.postgresql.util.PGobject
    implementation("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    // Test-only (ADR-013): generates the published contract from the controllers; the running
    // app serves no /v3/api-docs. 2.8.8 is the last release built on Spring Boot 3.4.
    testImplementation("org.springdoc:springdoc-openapi-starter-webmvc-api:2.8.8")
}

tasks.withType<Test> {
    useJUnitPlatform()
    outputs.dir("build/openapi")   // OpenApiContractTest writes the raw spec here (ADR-013)
}
