package zues.app.registry

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * The holdings port against real PostgreSQL: titles and residents written through the registry read back
 * through the port another module uses, as of a date. Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class HoldingsPersistenceIT {

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
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var holdings: Holdings

    private fun created(path: String, body: String) = json.readTree(
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated).andReturn().response.contentAsString,
    )

    private fun entrance() = UUID.fromString(
        created("/api/registry/entrances", """{"address":"ул. Раковски 1","label":"А","managementForm":"GA"}""").get("entranceId").asText(),
    )

    private fun units(entranceId: UUID): List<UUID> = created(
        "/api/registry/entrances/$entranceId/units",
        """{"units":[{"designation":"ап. 1","unitType":"FLAT","idealParts":"62.5000","separateEntrance":false},
                     {"designation":"ап. 2","unitType":"FLAT","idealParts":"37.5000","separateEntrance":false}]}""",
    ).get("unitIds").map { UUID.fromString(it.asText()) }

    private fun party(name: String) = UUID.fromString(
        created("/api/registry/parties", """{"fullName":"$name","idType":"EGN","idValue":"7501010010"}""").get("partyId").asText(),
    )

    private fun title(entranceId: UUID, unitId: UUID, partyId: UUID, share: String, from: String, to: String? = null, role: String = "OWN") = created(
        "/api/registry/entrances/$entranceId/units/$unitId/titles",
        """{"partyId":"$partyId","titleRole":"$role","share":"$share","validFrom":"$from","validTo":${to?.let { "\"$it\"" } ?: "null"}}""",
    )

    @Test
    fun `PM-ORG-005 PM-ORG-011 stored titles read back through the port as of a date, co-owners splitting the unit's parts exactly`() {
        val entranceId = entrance()
        val (first, second) = units(entranceId)
        val (ana, boris, seller, holderOfUse) = listOf("Ана", "Борис", "Продавач", "Ползвател").map { party(it) }
        title(entranceId, first, ana, "0.5", "2026-01-01")
        title(entranceId, first, boris, "0.5", "2026-01-01")
        title(entranceId, second, seller, "1", "2026-01-01", to = "2026-05-14")
        title(entranceId, second, ana, "1", "2026-05-14")
        title(entranceId, second, holderOfUse, "1", "2026-02-01", role = "USR")
        fun held(on: String, role: TitleRole = TitleRole.OWN) = holdings.inForce(entranceId, LocalDate.parse(on), role)
        fun weight(party: UUID, on: String, role: TitleRole = TitleRole.OWN) =
            held(on, role).holdings.filter { it.partyId == party }.sumOf { BigDecimal(it.idealParts) }

        // 13 May: Ana holds half of the first unit; the seller still owns the second
        assertThat(weight(ana, "2026-05-13")).isEqualByComparingTo("31.25")
        assertThat(weight(boris, "2026-05-13")).isEqualByComparingTo("31.25")
        assertThat(weight(seller, "2026-05-13")).isEqualByComparingTo("37.5")
        // 14 May, the day of sale: the second unit is Ana's, the seller holds nothing
        assertThat(weight(ana, "2026-05-14")).isEqualByComparingTo("68.75")
        assertThat(weight(seller, "2026-05-14")).isEqualByComparingTo("0")
        // owners together hold exactly the entrance's ideal parts on either day; the holder of use is answered apart
        for (day in listOf("2026-05-13", "2026-05-14")) {
            assertThat(held(day).holdings.sumOf { BigDecimal(it.idealParts) }).isEqualByComparingTo("100")
            assertThat(held(day).holdings.map { it.partyId }).doesNotContain(holderOfUse)
            assertThat(held(day, TitleRole.USR).holdings.map { it.partyId }).containsExactly(holderOfUse)
        }
        assertThat(weight(holderOfUse, "2026-01-15", role = TitleRole.USR)).isEqualByComparingTo("0")
        val half = held("2026-05-13").holdings.first { it.partyId == boris }
        assertThat(listOf(half.share, half.unitIdealParts, half.idealParts, half.idealPartsSource)).containsExactly("0.5", "62.5", "31.25", "DECLARED")
        assertThat(held("2026-05-14").overHeldUnitIds).isEmpty()

        // a third title over a unit already wholly owned: the unit is named from the day it begins, and only it
        title(entranceId, first, seller, "0.25", "2026-07-01")
        assertThat(held("2026-06-30").overHeldUnitIds).isEmpty()
        assertThat(held("2026-07-01").overHeldUnitIds).containsExactly(first)

        // another entrance's holdings are its own
        assertThat(holdings.inForce(entrance(), LocalDate.parse("2026-05-14"), TitleRole.OWN).holdings).isEmpty()
    }

    @Test
    fun `residents read back by party and unit as of a date, and nothing the port answers carries a name or an identity number`() {
        val entranceId = entrance()
        val (first, _) = units(entranceId)
        val resident = party("Мария Георгиева")
        val child = party("Петър Георгиев")
        val insert = "INSERT INTO registry.household_member(id, entrance_id, unit_id, party_id, is_child_under_6, valid_from, valid_to) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, ?, ?::date, ?::date)"
        jdbc.update(insert, entranceId, first, resident, false, "2026-01-01", null)
        jdbc.update(insert, entranceId, first, child, true, "2026-01-01", "2026-04-01")
        jdbc.update(insert, entranceId, first, null, false, "2026-01-01", null)                 // counted for charges, nobody the port can name

        assertThat(holdings.residents(entranceId, LocalDate.parse("2026-03-31")))
            .containsExactlyInAnyOrder(Resident(resident, first), Resident(child, first))
        val inMay = holdings.residents(entranceId, LocalDate.parse("2026-05-01"))
        assertThat(inMay).containsExactly(Resident(resident, first))
        assertThat(inMay.toString()).doesNotContain("Мария").doesNotContain("7501010010")
        title(entranceId, first, resident, "1", "2026-01-01")
        assertThat(holdings.inForce(entranceId, LocalDate.parse("2026-05-01"), TitleRole.OWN).toString()).doesNotContain("Мария").doesNotContain("7501010010").doesNotContain("ап. 1")
    }
}
