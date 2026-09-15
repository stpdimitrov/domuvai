package zues.app.registry

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.event.ApplicationEvents
import org.springframework.test.context.event.RecordApplicationEvents
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

/**
 * The persistence seam end to end against real PostgreSQL: HTTP → service → Spring Data
 * JDBC → the hand-written schema (applied by Flyway V1) → the outbox event.
 *
 * `disabledWithoutDocker = true` skips the whole class where no Docker daemon is present
 * (this machine, so the gate pack stays green locally); it runs in CI, where Docker is.
 * MockMvc drives the request on the test thread, so `@RecordApplicationEvents` sees the
 * event published inside the service transaction.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
class RegistryPersistenceIT {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(DockerImageName.parse("postgres:16"))

        @DynamicPropertySource
        @JvmStatic
        fun datasource(registry: DynamicPropertyRegistry) {
            val schemas = "registry,identity_org,assembly,money,maintenance,compliance,evidence,app,public"
            registry.add("spring.datasource.url") { "${postgres.jdbcUrl}&currentSchema=$schemas" }
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper

    @Test
    fun `POST persists an entrance, GET lists it, and the event is published`(events: ApplicationEvents) {
        mvc.perform(
            post("/api/registry/entrances")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(RegisterEntranceRequest("ул. Раковски 1", "А", "GA"))),
        )
            .andExpect(status().isCreated)

        mvc.perform(get("/api/registry/entrances"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].label").value("А"))

        assertThat(events.stream(EntranceRegistered::class.java).count()).isEqualTo(1)
    }
}
