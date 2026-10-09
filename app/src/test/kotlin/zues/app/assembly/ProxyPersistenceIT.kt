package zues.app.assembly

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import zues.kernel.toSofiaDate
import zues.law.constantOn
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Proxies against real PostgreSQL, through the HTTP layer: what is captured reads back, the service's limit holds
 * over stored rows, and the table refuses a malformed row. The limit itself is the service's, not the table's. Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class ProxyPersistenceIT {

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

    private fun created(path: String, body: String): JsonNode = json.readTree(
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated).andReturn().response.contentAsString,
    )

    private fun entrance() = UUID.fromString(
        created("/api/registry/entrances", """{"address":"ул. Раковски 1","label":"А","managementForm":"GA"}""").get("entranceId").asText(),
    )

    private fun party() = UUID.fromString(created("/api/registry/parties", """{"fullName":"Иван Петров"}""").get("partyId").asText())

    /** A month from now: far enough for any notice period, whenever this runs. */
    private val meets: Instant = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)

    private fun convening(convenor: UUID, urgent: String = """"urgent":false""", at: Instant = meets) =
        """{"convenedBy":"$convenor","convenedAs":"BM","scheduledAt":"$at","place":"фоайето на вх. А","mode":"IN_PERSON",$urgent}"""

    private fun read(base: String, id: String): JsonNode =
        json.readTree(mvc.perform(get("$base/$id")).andExpect(status().isOk).andReturn().response.contentAsString)

    private fun assembly(entranceId: UUID): String {
        val base = "/api/assembly/entrances/$entranceId/assemblies"
        val id = created(base, convening(party())).get("id").asText()
        created("$base/$id/agenda", """{"text":"Отчет","itemType":"GENERAL"}""")
        created("$base/$id/agenda", """{"text":"Ремонт на покрива","itemType":"GENERAL"}""")
        return "$base/$id/proxies"
    }

    private fun proxy(principal: UUID, agent: UUID, rest: String = """"agentKind":"OWNER","scope":"WHOLE_AGENDA","form":"WRITTEN"""") =
        """{"principalPartyId":"$principal","agentPartyId":"$agent",$rest}"""

    @Test
    fun `PM-GA-009 a registered proxy reads back with its principal, agent, scope and form, and the table refuses a malformed one`() {
        val proxies = assembly(entrance())
        val principal = party()
        val agent = party()
        created(proxies, proxy(principal, agent, """"agentKind":"THIRD_PARTY","scope":"LISTED_ITEMS","items":[2],"form":"NOTARISED""""))
        val read = json.readTree(mvc.perform(get(proxies)).andExpect(status().isOk).andReturn().response.contentAsString).single()
        assertThat(read.get("principalPartyId").asText()).isEqualTo(principal.toString())
        assertThat(read.get("agentPartyId").asText()).isEqualTo(agent.toString())
        assertThat(read.get("agentKind").asText()).isEqualTo("THIRD_PARTY")
        assertThat(read.get("scope").asText()).isEqualTo("LISTED_ITEMS")
        assertThat(read.get("items").map { it.asInt() }).containsExactly(2)
        assertThat(read.get("form").asText()).isEqualTo("NOTARISED")

        // the caller's errors: a second proxy for the principal, an item not on the agenda, a party nobody registered
        mvc.perform(post(proxies).contentType(MediaType.APPLICATION_JSON).content(proxy(principal, party()))).andExpect(status().isConflict)
        mvc.perform(
            post(proxies).contentType(MediaType.APPLICATION_JSON)
                .content(proxy(party(), agent, """"agentKind":"OWNER","scope":"LISTED_ITEMS","items":[3],"form":"WRITTEN"""")),
        ).andExpect(status().isBadRequest)
        mvc.perform(post(proxies).contentType(MediaType.APPLICATION_JSON).content(proxy(party(), UUID.randomUUID()))).andExpect(status().isBadRequest)

        // and the table's own refusals, each by its constraint
        val copy = "INSERT INTO assembly.proxy SELECT gen_random_uuid(), entrance_id, assembly_id, %s, %s, agent_kind, %s, %s, form, registered_at FROM assembly.proxy"
        fun refused(principalSql: String, agentSql: String, scope: String, items: String, constraint: String) =
            assertThatThrownBy { jdbc.update(copy.format(principalSql, agentSql, scope, items)) }
                .isInstanceOf(DataIntegrityViolationException::class.java).hasMessageContaining(constraint)
        val other = "'${party()}'::uuid"
        refused("principal_party_id", "agent_party_id", "scope", "scope_items", "proxy_one_per_principal")
        refused(other, other, "scope", "scope_items", "proxy_not_for_oneself")
        refused(other, "agent_party_id", "'WHOLE_AGENDA'", "scope_items", "proxy_scope_lists_its_items")
        refused(other, "agent_party_id", "'LISTED_ITEMS'", "NULL", "proxy_scope_lists_its_items")
        refused(other, "agent_party_id", "scope", "'2;3'", "proxy_scope_items_check")
        assertThat(jdbc.update(copy.format(other, "agent_party_id", "scope", "scope_items"))).isEqualTo(1)      // the control: a well-formed copy goes in
    }

    @Test
    fun `PM-GA-010 a registration past the limit for one agent is a 409 showing the limit, and nothing more is stored`() {
        val proxies = assembly(entrance())
        val agent = party()
        val limit = constantOn("GA_PROXY_MAX_PRINCIPALS", toSofiaDate(meets)).value.toInt()
        repeat(limit) { created(proxies, proxy(party(), agent)) }
        val refusal = json.readTree(
            mvc.perform(post(proxies).contentType(MediaType.APPLICATION_JSON).content(proxy(party(), agent)))
                .andExpect(status().isConflict).andReturn().response.contentAsString,
        )
        assertThat(refusal.get("limit").asInt()).isEqualTo(limit)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM assembly.proxy WHERE agent_party_id = ?", Int::class.java, agent)).isEqualTo(limit)

        // the limit is per assembly: the same agent may represent at another one
        created(assembly(entrance()), proxy(party(), agent))

        // through another entrance's path this assembly's proxies are neither listed nor added to
        val elsewhere = proxies.replace(Regex("entrances/[^/]+"), "entrances/${entrance()}")
        mvc.perform(get(elsewhere)).andExpect(status().isNotFound)
        mvc.perform(post(elsewhere).contentType(MediaType.APPLICATION_JSON).content(proxy(party(), party()))).andExpect(status().isNotFound)
    }
}
