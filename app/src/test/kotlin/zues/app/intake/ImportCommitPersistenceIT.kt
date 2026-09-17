package zues.app.intake

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import zues.app.registry.ImportAdoption
import zues.app.registry.PropertyUnitRepository
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * The commit seam against real PostgreSQL, proved in two halves so no test depends on the async
 * hop between them:
 *  - the intake HTTP lifecycle: a reviewed import commits (its file's hash must match) and reverts,
 *    and its status transitions REPRODUCED → COMMITTED → REVERTED;
 *  - the registry's reaction: the listener adopts the units (stamped with the import id, typed
 *    UNSPECIFIED until the pilot sheet, summing to 100% — PM-ORG-002 — under their entrance —
 *    PM-ORG-001), is idempotent on redelivery, and drops them on revert.
 * The publish itself is proved by ImportServiceTest; Spring Modulith delivers between the two.
 * Docker-gated — skips locally, runs in CI.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class ImportCommitPersistenceIT {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(DockerImageName.parse("postgres:16"))

        @DynamicPropertySource
        @JvmStatic
        fun datasource(registry: DynamicPropertyRegistry) {
            val schemas = "registry,identity_org,assembly,money,maintenance,compliance,evidence,app,intake,public"
            registry.add("spring.datasource.url") { "${postgres.jdbcUrl}&currentSchema=$schemas" }
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var units: PropertyUnitRepository
    @Autowired lateinit var adoption: ImportAdoption

    private fun createEntrance(): UUID {
        val response = mvc.perform(
            post("/api/registry/entrances").contentType(MediaType.APPLICATION_JSON)
                .content("""{"address":"ул. Раковски 1","label":"А","managementForm":"GA"}"""),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return UUID.fromString(json.readTree(response).get("entranceId").asText())
    }

    // A reproduced sheet: 60/40 of a 10 000-minor maintenance total → 6000/4000, cent-exact.
    private val sheet = """{"period":"2026-05","legalDate":"2026-05-01",
        "lines":[{"stream":"MAINTENANCE","key":"BY_IDEAL_PARTS","decisionId":"GA-2026-1","totalMinor":10000}],
        "csv":"designation,ideal_parts,occupants,fee_minor\nап. 1,60.0000,2,6000\nап. 2,40.0000,1,4000"}"""

    private fun recordImport(entranceId: UUID): String {
        val created = mvc.perform(
            post("/api/intake/entrances/$entranceId/imports").contentType(MediaType.APPLICATION_JSON).content(sheet),
        ).andExpect(status().isCreated).andExpect(jsonPath("$.report.reproduced").value(true))
            .andReturn().response.contentAsString
        return json.readTree(created).get("importId").asText()
    }

    @Test
    fun `a reviewed import commits and reverts through its status lifecycle`() {
        val entranceId = createEntrance()
        val importId = recordImport(entranceId)

        mvc.perform(
            post("/api/intake/imports/$importId/commit").contentType(MediaType.APPLICATION_JSON)
                .content("""{"committedBy":"${UUID.randomUUID()}","sheet":$sheet}"""),
        ).andExpect(status().isOk).andExpect(jsonPath("$.rowsCreated").value(2))

        mvc.perform(get("/api/intake/imports/$importId")).andExpect(jsonPath("$.status").value("COMMITTED"))

        // Committing again is refused — a committed import is not re-committed (409).
        mvc.perform(
            post("/api/intake/imports/$importId/commit").contentType(MediaType.APPLICATION_JSON)
                .content("""{"committedBy":"${UUID.randomUUID()}","sheet":$sheet}"""),
        ).andExpect(status().isConflict)

        mvc.perform(
            post("/api/intake/imports/$importId/revert").contentType(MediaType.APPLICATION_JSON)
                .content("""{"revertedBy":"${UUID.randomUUID()}","reason":"pilot re-import"}"""),
        ).andExpect(status().isOk).andExpect(jsonPath("$.status").value("REVERTED"))
    }

    @Test
    fun `the registry adopts a committed import's units, idempotently, and drops them on revert (PM-ORG-001, PM-ORG-002)`() {
        val entranceId = createEntrance()
        val importId = UUID.randomUUID()
        val event = ImportCommitted(
            entranceId, importId, importId, UUID.randomUUID(), 2, 0,
            listOf(AdoptedUnit("об. 1", "70.0000"), AdoptedUnit("об. 2", "30.0000")),
        )

        adoption.on(event)

        val adopted = units.findByEntranceId(entranceId)
        assertThat(adopted).hasSize(2)                                              // PM-ORG-001: under the entrance
        assertThat(adopted.map { it.designation }).containsExactlyInAnyOrder("об. 1", "об. 2")
        assertThat(adopted).allSatisfy { assertThat(it.importId).isEqualTo(importId) }
        assertThat(adopted).allSatisfy { assertThat(it.unitType).isEqualTo("UNSPECIFIED") }
        assertThat(adopted.map { it.idealPartsPct }.reduce(BigDecimal::add)).isEqualByComparingTo(BigDecimal("100.0000"))

        adoption.on(event)                                                         // redelivery is idempotent
        assertThat(units.findByImportId(importId)).hasSize(2)

        adoption.on(ImportReverted(entranceId, importId, UUID.randomUUID(), Instant.parse("2026-06-01T00:00:00Z"), "undo"))
        assertThat(units.findByImportId(importId)).isEmpty()
    }

    @Test
    fun `the registry refuses to adopt units that do not sum to 100 percent (PM-ORG-002)`() {
        val entranceId = createEntrance()
        assertThatThrownBy {
            adoption.on(
                ImportCommitted(
                    entranceId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 2, 0,
                    listOf(AdoptedUnit("об. 1", "60.0000"), AdoptedUnit("об. 2", "30.0000")),   // 90%, not 100
                ),
            )
        }.isInstanceOf(RuntimeException::class.java)
        assertThat(units.findByEntranceId(entranceId)).isEmpty()
    }
}
