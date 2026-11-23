package zues.app.money

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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.LocalDate
import java.util.UUID

/** Tariff versions through the real `GaDecisions` port, over real rows (PM-FEE-012). [TariffVersionTest] has the rest. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class TariffVersionIT {

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

    private fun send(path: String, body: String) = mvc.perform(post(path).header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(body))

    private fun entrance(): UUID {
        val made = send("/api/registry/entrances", """{"address":"ул. Раковски 1","label":"А","managementForm":"GA"}""")
            .andExpect(status().isCreated).andReturn().response.contentAsString
        return UUID.fromString(json.readTree(made).get("entranceId").asText())
    }

    private fun paperDecision(entrance: UUID): String {
        val recorded = send(
            "/api/assembly/entrances/$entrance/paper-decisions",
            """{"decisionText":"Вноска за поддръжка","protocolDate":"2026-12-10","protocolRef":"Протокол 1","archiveLocation":"Папка","custodian":"Мария Иванова"}""",
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return json.readTree(recorded).get("id").asText()
    }

    private fun record(entrance: UUID, decisionId: String, from: String) = send(
        "/api/money/entrances/$entrance/tariff-versions",
        """{"decisionId":"$decisionId","validFrom":"$from","lines":[{"stream":"MAINTENANCE","key":"BY_IDEAL_PARTS","totalMinor":10000}]}""",
    )

    @Test
    fun `PM-FEE-012 a version recorded on a paper decision is read back with its decision and months, the later ending the earlier`() {
        val entrance = entrance()
        val decision = paperDecision(entrance)
        record(entrance, decision, "2027-01").andExpect(status().isCreated)
        record(entrance, decision, "2027-07").andExpect(status().isCreated)
        record(entrance, decision, "2027-07").andExpect(status().isConflict)

        val path = "/api/money/entrances/$entrance/tariff-versions"
        mvc.perform(get(path)).andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2)).andExpect(jsonPath("$[0].validFrom").value("2027-07"))
        mvc.perform(get(path).param("period", "2027-03")).andExpect(status().isOk)
            .andExpect(jsonPath("$[0].validFrom").value("2027-01")).andExpect(jsonPath("$[0].decisionId").value(decision))
            .andExpect(jsonPath("$[0].lines[0].totalMinor").value(10000))
        mvc.perform(get(path).param("period", "2026-12")).andExpect(status().isOk).andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `PM-SEC-004 the tariff versions are not emptied, and the rows remain`() {
        val entrance = entrance()
        record(entrance, paperDecision(entrance), "2027-01").andExpect(status().isCreated)
        org.assertj.core.api.Assertions.assertThatThrownBy { jdbc.execute("TRUNCATE money.tariff_version") }.isInstanceOf(org.springframework.dao.DataAccessException::class.java).hasMessageContaining("is not emptied")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM money.tariff_version WHERE entrance_id = ?", Int::class.java, entrance)).isEqualTo(1)
    }

    @Test
    fun `PM-FEE-012 a version on a made-up id or another entrance's decision is refused, and a stored one is never altered`() {
        val entrance = entrance()
        for (cited in listOf("GA-2027-1", UUID.randomUUID().toString(), paperDecision(entrance()))) {
            val refused = record(entrance, cited, "2027-01").andExpect(status().isBadRequest).andReturn().response.contentAsString
            assertThat(refused).contains(cited).contains("PM-FEE-012")
        }
        record(entrance, paperDecision(entrance), "2027-01").andExpect(status().isCreated)
        jdbc.update("UPDATE money.tariff_version SET valid_from = '2027-02-01' WHERE entrance_id = ?", entrance)
        jdbc.update("DELETE FROM money.tariff_version WHERE entrance_id = ?", entrance)
        assertThat(jdbc.queryForObject("SELECT valid_from FROM money.tariff_version WHERE entrance_id = ?", LocalDate::class.java, entrance))
            .isEqualTo(LocalDate.of(2027, 1, 1))
    }
}
