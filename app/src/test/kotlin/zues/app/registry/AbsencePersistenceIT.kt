package zues.app.registry

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import zues.law.numberOn
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Absence persistence against real PostgreSQL, read back through the port money uses: a filed
 * declaration raises the `absentDays` the adapter surfaces (PM-FEE-006), which the engine turns
 * into an exemption. The clock is pinned so `filedOn` is deterministic and the filing is timely
 * (PM-FEE-007) regardless of the wall clock. Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@Import(AbsencePersistenceIT.FixedClock::class)
class AbsencePersistenceIT {

    @TestConfiguration
    class FixedClock {
        // A charge run reads the law at its own date, but a filing is stamped by the system clock;
        // pin it inside the absence's grace window so the test does not depend on the day it runs.
        @Bean @Primary
        fun fixedClock(): Clock = Clock.fixed(Instant.parse("2026-05-15T00:00:00Z"), ZoneOffset.UTC)
    }

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
    @Autowired lateinit var units: Units

    private fun createEntrance(): UUID {
        val response = mvc.perform(
            post("/api/registry/entrances").contentType(MediaType.APPLICATION_JSON)
                .content("""{"address":"ул. Раковски 1","label":"А","managementForm":"GA"}"""),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return UUID.fromString(json.readTree(response).get("entranceId").asText())
    }

    private fun registerSingleUnit(entranceId: UUID): UUID {
        val response = mvc.perform(
            post("/api/registry/entrances/$entranceId/units").contentType(MediaType.APPLICATION_JSON)
                .content("""{"units":[{"designation":"ап. 1","unitType":"FLAT","idealParts":"100.0000","separateEntrance":false}]}"""),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return UUID.fromString(json.readTree(response).get("unitIds").get(0).asText())
    }

    @Test
    fun `a filed absence is persisted and surfaced through the port as days that exempt the unit`() {
        val entranceId = createEntrance()
        val unitId = registerSingleUnit(entranceId)

        // one resident — without the absence, the unit is billable per person
        mvc.perform(
            post("/api/registry/entrances/$entranceId/units/$unitId/household")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"members":[{"isChildUnder6":false,"validFrom":"2026-01-01"}]}"""),
        ).andExpect(status().isCreated)

        // a filed absence, Feb–May 2026; the pinned clock stamps filedOn 2026-05-15, within the window
        mvc.perform(
            post("/api/registry/entrances/$entranceId/units/$unitId/absences")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"absences":[{"absentFrom":"2026-02-01","absentTo":"2026-05-01"}]}"""),
        ).andExpect(status().isCreated)

        val unit = units.forEntrance(entranceId, LocalDate.of(2026, 5, 15)).single { it.unitId == unitId }
        assertThat(unit.occupants).isEqualTo(1)
        val declaredDays = ChronoUnit.DAYS.between(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 5, 1)).toInt()
        assertThat(unit.absentDays).isEqualTo(declaredDays)
        assertThat(unit.absentDays).isGreaterThan(numberOn("ABSENCE_EXEMPTION_DAYS", "2026-05-15").toInt())
    }
}
