package zues.app.registry

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
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
import java.time.LocalDate
import java.util.UUID

/**
 * A move-out and the retention after it against real PostgreSQL, read back through the port money
 * uses: the closed range stops the count (PM-BOOK-008), and the pass three months after the move-out
 * was recorded — not after the declared, earlier day — removes the identifiers while the counts a
 * charge was computed from stay (PM-BOOK-010). Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class BookRetentionPersistenceIT {

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
    @Autowired lateinit var retention: BookRetentionService
    @Autowired lateinit var aggregates: JdbcAggregateTemplate
    @Autowired lateinit var household: HouseholdMemberRepository
    @Autowired lateinit var animals: AnimalRepository

    private fun postFor(path: String, body: String, field: String): UUID {
        val response = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated).andReturn().response.contentAsString
        val node = json.readTree(response).get(field)
        return UUID.fromString((if (node.isArray) node.get(0) else node).asText())
    }

    private fun endStay(path: String) {
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("""{"on":"2026-05-01"}"""))
            .andExpect(status().isOk)
    }

    private fun countedOn(entranceId: UUID, day: String) = units.forEntrance(entranceId, LocalDate.parse(day)).single()

    @Test
    fun `PM-BOOK-008 PM-BOOK-010 a move-out closes the stay, and three months after the record the book drops who it was, not what was charged`() {
        val entranceId = postFor("/api/registry/entrances", """{"address":"ул. Цар Симеон 9","label":"А","managementForm":"GA"}""", "entranceId")
        val unitId = postFor(
            "/api/registry/entrances/$entranceId/units",
            """{"units":[{"designation":"ап. 1","unitType":"FLAT","idealParts":"100.0000","separateEntrance":false}]}""", "unitIds",
        )
        val partyId = postFor("/api/registry/parties", """{"fullName":"Петър Илиев"}""", "partyId")
        val resident = aggregates.insert(
            HouseholdMember(UUID.randomUUID(), entranceId, unitId, partyId, false, LocalDate.parse("2026-01-01"), null),
        )
        val animalId = postFor(
            "/api/registry/entrances/$entranceId/units/$unitId/animals",
            """{"animals":[{"species":"cat","vetPassportNo":"VP-7","validFrom":"2026-01-01"}]}""", "animalIds",
        )

        endStay("/api/registry/entrances/$entranceId/units/$unitId/household/${resident.id}/end")
        endStay("/api/registry/entrances/$entranceId/units/$unitId/animals/$animalId/end")
        assertThat(countedOn(entranceId, "2026-06-01").occupants).isZero()               // gone from the count after the move-out
        assertThat(countedOn(entranceId, "2026-06-01").animals).isZero()

        val recorded = listOf(household.findById(resident.id).get().endRecordedOn!!, animals.findById(animalId).get().endRecordedOn!!)
        val early = retention.anonymiseDue(entranceId, recorded.min().plusMonths(3).minusDays(1))   // 1 May is long past; the record is not
        assertThat(early.householdUnlinked + early.animalPassportsCleared).isZero()
        val applied = retention.anonymiseDue(entranceId, recorded.max().plusMonths(3))
        assertThat(applied.householdUnlinked).isEqualTo(1)
        assertThat(applied.animalPassportsCleared).isEqualTo(1)

        assertThat(household.findById(resident.id).get().partyId).isNull()
        assertThat(animals.findById(animalId).get().vetPassportNo).isNull()
        assertThat(animals.findById(animalId).get().species).isEqualTo("cat")
        assertThat(countedOn(entranceId, "2026-04-01").occupants).isEqualTo(1)            // what April was charged on still holds
        assertThat(countedOn(entranceId, "2026-04-01").animals).isEqualTo(1)
        assertThat(aggregates.findById(partyId, Party::class.java)?.fullName).isEqualTo("Петър Илиев")   // the person record is not this slice's
    }
}
