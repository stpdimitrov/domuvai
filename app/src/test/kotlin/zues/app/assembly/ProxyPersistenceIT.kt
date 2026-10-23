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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import zues.app.identity_org.Logins
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

    @Autowired lateinit var logins: Logins
    private val realm = "https://id.example.test/realms/${UUID.randomUUID()}"

    /** Whose requests these are: every request below carries this login's token, until another person is signed in. */
    private var signedInAs: String? = null

    private fun post(path: String) = MockMvcRequestBuilders.post(path).also { request -> signedInAs?.let { request.with(jwt().jwt { token -> token.subject(it).issuer(realm) }) } }

    /** A party with a login of its own, tied to it. */
    private fun person(): UUID = party().also { logins.tie(realm, "login-$it", it) }

    private fun <T> signedIn(party: UUID?, act: () -> T): T {
        val before = signedInAs
        signedInAs = party?.let { "login-$it" }
        try { return act() } finally { signedInAs = before }
    }

    /** The entrance's manager, with the mandate in force — and signed in from here on. */
    private fun manager(entranceId: UUID, body: String = "BM", from: Int = -30, to: Int = 300): UUID = person().also {
        jdbc.update(
            sofiaDated("INSERT INTO identity_org.management_mandate (id, entrance_id, body, party_id, valid_from, valid_to) VALUES (gen_random_uuid(), ?, ?, ?, current_date + ?, current_date + ?)"),
            entranceId, body, it, from, to,
        )
        signedInAs = "login-$it"
    }

    /**
     * The book as a fixture enters it: by nobody signed in, as sign-in switched off allows — whoever is signed in for the
     * test is not the one entering the book (PM-BOOK-005: the manager or the board do).
     */
    private fun entered(path: String, body: String): JsonNode = signedIn(null) { created(path, body) }

    /** An owner of a unit of the entrance since the start of last year — the whole entrance, the first time it is asked for. */
    private fun owner(entranceId: UUID): UUID = person().also {
        val existing = jdbc.queryForList("SELECT id FROM registry.unit WHERE entrance_id = ?", UUID::class.java, entranceId)
        val unit = existing.firstOrNull() ?: UUID.fromString(
            entered("/api/registry/entrances/$entranceId/units", """{"units":[{"designation":"ап. 1","unitType":"FLAT","idealParts":"100.0000","separateEntrance":false}]}""")
                .get("unitIds")[0].asText(),
        )
        entered("/api/registry/entrances/$entranceId/units/$unit/titles", """{"partyId":"$it","titleRole":"OWN","share":"${if (existing.isEmpty()) "0.5" else "0.25"}","validFrom":"2025-01-01"}""")
    }

    /** A month from now: far enough for any notice period, whenever this runs. */
    private val meets: Instant = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)

    private fun convening(convenor: UUID, urgent: String = """"urgent":false""", at: Instant = meets) =
        """{"convenedBy":"$convenor","convenedAs":"BM","scheduledAt":"$at","place":"фоайето на вх. А","mode":"IN_PERSON",$urgent}"""

    private fun read(base: String, id: String): JsonNode =
        json.readTree(mvc.perform(get("$base/$id")).andExpect(status().isOk).andReturn().response.contentAsString)

    private fun assembly(entranceId: UUID): String {
        val base = "/api/assembly/entrances/$entranceId/assemblies"
        val id = created(base, convening(manager(entranceId))).get("id").asText()
        created("$base/$id/agenda", """{"text":"Отчет","itemType":"GENERAL"}""")
        created("$base/$id/agenda", """{"text":"Ремонт на покрива","itemType":"GENERAL"}""")
        return "$base/$id/proxies"
    }

    private fun appointing(agent: UUID, rest: String = """"agentKind":"OWNER","scope":"WHOLE_AGENDA","form":"WRITTEN"""") = """{"agentPartyId":"$agent",$rest}"""

    /** The principal, signed in, appoints. */
    private fun appoint(proxies: String, principal: UUID, agent: UUID, rest: String = """"agentKind":"OWNER","scope":"WHOLE_AGENDA","form":"WRITTEN"""") =
        signedIn(principal) { mvc.perform(post(proxies).contentType(MediaType.APPLICATION_JSON).content(appointing(agent, rest))) }

    private fun entranceOf(proxies: String) = UUID.fromString(Regex("entrances/([^/]+)").find(proxies)!!.groupValues[1])

    @Test
    fun `PM-GA-009 an appointed proxy reads back with its principal, agent, scope and form, names its sign-in, and the table refuses a malformed one`() {
        val proxies = assembly(entrance())
        val entranceId = entranceOf(proxies)
        val principal = owner(entranceId)
        val agent = party()
        appoint(proxies, principal, agent, """"agentKind":"THIRD_PARTY","scope":"LISTED_ITEMS","items":[2],"form":"NOTARISED"""").andExpect(status().isCreated)
        val read = json.readTree(mvc.perform(get(proxies)).andExpect(status().isOk).andReturn().response.contentAsString).single()
        assertThat(read.get("principalPartyId").asText()).isEqualTo(principal.toString())
        assertThat(read.get("agentPartyId").asText()).isEqualTo(agent.toString())
        assertThat(read.get("agentKind").asText()).isEqualTo("THIRD_PARTY")
        assertThat(read.get("scope").asText()).isEqualTo("LISTED_ITEMS")
        assertThat(read.get("items").map { it.asInt() }).containsExactly(2)
        assertThat(read.get("form").asText()).isEqualTo("NOTARISED")
        assertThat(jdbc.queryForMap("SELECT login_issuer, login_subject FROM assembly.proxy WHERE principal_party_id = ?", principal))
            .containsEntry("login_issuer", realm).containsEntry("login_subject", "login-$principal")

        // the caller's errors: a second proxy for the principal, an item not on the agenda, an agent nobody registered
        appoint(proxies, principal, owner(entranceId)).andExpect(status().isConflict)
        appoint(proxies, owner(entranceId), owner(entranceId), """"agentKind":"OWNER","scope":"LISTED_ITEMS","items":[3],"form":"WRITTEN"""").andExpect(status().isBadRequest)
        appoint(proxies, owner(entranceId), UUID.randomUUID(), """"agentKind":"THIRD_PARTY","scope":"WHOLE_AGENDA","form":"WRITTEN"""").andExpect(status().isBadRequest)

        // and the table's own refusals, each by its constraint
        val copy = "INSERT INTO assembly.proxy SELECT gen_random_uuid(), entrance_id, assembly_id, %s, %s, agent_kind, %s, %s, form, registered_at, %s, login_subject, %s, authorisation_hash FROM assembly.proxy WHERE principal_party_id = '$principal'"
        fun refused(principalSql: String, agentSql: String, scope: String, items: String, constraint: String, login: String = "login_issuer") =
            assertThatThrownBy { jdbc.update(copy.format(principalSql, agentSql, scope, items, login, principalSql)) }
                .isInstanceOf(DataIntegrityViolationException::class.java).hasMessageContaining(constraint)
        val other = "'${party()}'::uuid"
        refused("principal_party_id", "agent_party_id", "scope", "scope_items", "proxy_one_per_principal")
        refused(other, other, "scope", "scope_items", "proxy_not_for_oneself")
        refused(other, "agent_party_id", "'WHOLE_AGENDA'", "scope_items", "proxy_scope_lists_its_items")
        refused(other, "agent_party_id", "'LISTED_ITEMS'", "NULL", "proxy_scope_lists_its_items")
        refused(other, "agent_party_id", "scope", "'2;3'", "proxy_scope_items_check")
        refused(other, "agent_party_id", "scope", "scope_items", "proxy_names_its_login", login = "NULL")
        // recorded for somebody else, it carries its document; in person, none
        assertThatThrownBy { jdbc.update(copy.format(other, "agent_party_id", "scope", "scope_items", "login_issuer", "agent_party_id")) }
            .isInstanceOf(DataIntegrityViolationException::class.java).hasMessageContaining("proxy_recorded_with_its_authorisation")
        assertThat(jdbc.update(copy.format(other, "agent_party_id", "scope", "scope_items", "login_issuer", other))).isEqualTo(1)      // the control: a well-formed copy goes in
    }

    @Test
    fun `PM-GA-009 PM-SEC-001 what the audit could do is refused - a principal named in the body, a principal with no title, an owner who owns nothing, a household member who lives elsewhere`() {
        val proxies = assembly(entrance())
        val entranceId = entranceOf(proxies)
        val bothUnits = entered(
            "/api/registry/entrances/$entranceId/units",
            """{"units":[{"designation":"ап. 1","unitType":"FLAT","idealParts":"60.0000","separateEntrance":false},
                         {"designation":"ап. 2","unitType":"FLAT","idealParts":"40.0000","separateEntrance":false}]}""",
        ).get("unitIds").map { UUID.fromString(it.asText()) }
        val owner = owner(entranceId)
        val agent = owner(entranceId)
        val unit = jdbc.queryForObject("SELECT unit_id FROM registry.title WHERE party_id = ?", UUID::class.java, owner)!!
        val otherUnit = bothUnits.single { it != unit }
        val stranger = person()
        val manager = jdbc.queryForObject("SELECT party_id FROM identity_org.management_mandate WHERE entrance_id = ?", UUID::class.java, entranceId)!!
        val naming = """{"principalPartyId":"$owner","agentPartyId":"$agent","agentKind":"OWNER","scope":"WHOLE_AGENDA","form":"WRITTEN"}"""

        // nobody appoints for the owner by naming them: a stranger, a login tied to no party, nobody at all, an owner next door, another owner,
        // the controller — with a document or without
        val controller = person().also {
            jdbc.update(sofiaDated("INSERT INTO identity_org.management_mandate (id, entrance_id, body, party_id, valid_from, valid_to) VALUES (gen_random_uuid(), ?, 'CTL', ?, current_date - 30, current_date + 300)"), entranceId, it)
        }
        val document = "5d".repeat(32)
        val withDocument = naming.replace("{", """{"authorisationHash":"$document",""")
        for (who in listOf(stranger, agent, controller, owner(entrance()))) {
            signedIn(who) { mvc.perform(post(proxies).contentType(MediaType.APPLICATION_JSON).content(withDocument)).andExpect(status().isForbidden) }
        }
        // the manager convened this assembly and runs it: without the document they record nothing
        signedIn(manager) { mvc.perform(post(proxies).contentType(MediaType.APPLICATION_JSON).content(naming)).andExpect(status().isBadRequest) }
        for (who in listOf(stranger, owner(entrance()))) {
            signedIn(who) { mvc.perform(post(proxies).contentType(MediaType.APPLICATION_JSON).content(naming)).andExpect(status().isForbidden) }
        }
        signedInAs = "login-tied-to-nobody-${UUID.randomUUID()}"
        mvc.perform(post(proxies).contentType(MediaType.APPLICATION_JSON).content(naming)).andExpect(status().isForbidden)
        signedIn(null) { mvc.perform(post(proxies).contentType(MediaType.APPLICATION_JSON).content(naming)).andExpect(status().isForbidden) }
        // a former owner is no principal
        val former = person().also {
            jdbc.update(sofiaDated("INSERT INTO registry.title (id, entrance_id, unit_id, party_id, title_role, share, valid_from, valid_to) VALUES (gen_random_uuid(), ?, ?, ?, 'OWN', 0.000001, current_date - 400, current_date - 1)"), entranceId, unit, it)
        }
        appoint(proxies, former, agent).andExpect(status().isForbidden)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM assembly.proxy WHERE entrance_id = ?", Int::class.java, entranceId)).isZero()

        // finding 4: "another owner" must own; "a household member" must live in the principal's unit
        appoint(proxies, owner, stranger).andExpect(status().isBadRequest)                                     // called an owner, owns nothing
        appoint(proxies, owner, former).andExpect(status().isBadRequest)                                       // owned until yesterday
        val member = """"agentKind":"HOUSEHOLD_MEMBER","scope":"WHOLE_AGENDA","form":"WRITTEN""""
        appoint(proxies, owner, stranger, member).andExpect(status().isBadRequest)                             // lives nowhere the book knows
        val resident = "INSERT INTO registry.household_member (id, entrance_id, unit_id, party_id, is_child_under_6, valid_from, valid_to) VALUES (gen_random_uuid(), ?, ?, ?, false, current_date - 100, NULL)"
        val atHome = person().also { jdbc.update(sofiaDated(resident), entranceId, unit, it) }
        appoint(proxies, owner, atHome, member).andExpect(status().isBadRequest)                               // the owner is not in the book as living there
        jdbc.update(sofiaDated(resident), entranceId, unit, owner)
        val movedOut = person().also {
            jdbc.update(sofiaDated(resident.replace("current_date - 100, NULL", "current_date - 100, current_date - 1")), entranceId, unit, it)
        }
        appoint(proxies, owner, movedOut, member).andExpect(status().isBadRequest)
        // a resident of another unit of the entrance is not of this household; an owner next door is not "another owner" here
        val acrossTheLanding = person().also { jdbc.update(sofiaDated(resident), entranceId, otherUnit, it) }
        appoint(proxies, owner, acrossTheLanding, member).andExpect(status().isBadRequest)
        appoint(proxies, owner, owner(entrance())).andExpect(status().isBadRequest)
        // somebody whose title begins tomorrow is not an owner today: neither appoints nor is "another owner"
        val buyer = person().also {
            jdbc.update(sofiaDated("INSERT INTO registry.title (id, entrance_id, unit_id, party_id, title_role, share, valid_from) VALUES (gen_random_uuid(), ?, ?, ?, 'OWN', 0.000001, current_date + 1)"), entranceId, unit, it)
        }
        appoint(proxies, buyer, agent).andExpect(status().isForbidden)
        appoint(proxies, owner, buyer).andExpect(status().isBadRequest)
        // an owner naming another principal is refused — they do not run the assembly — and it is not made the caller's own
        signedIn(owner) { mvc.perform(post(proxies).contentType(MediaType.APPLICATION_JSON).content(naming.replace("$owner", "$agent"))).andExpect(status().isForbidden) }
        appoint(proxies, owner, atHome, member).andExpect(status().isCreated)
        // a holder of a right of use appoints too, and another owner represents them
        val holderOfUse = person().also {
            entered("/api/registry/entrances/$entranceId/units/$unit/titles", """{"partyId":"$it","titleRole":"USR","share":"1","validFrom":"2025-01-01"}""")
        }
        appoint(proxies, holderOfUse, agent).andExpect(status().isCreated)
        assertThat(jdbc.queryForList("SELECT principal_party_id FROM assembly.proxy WHERE entrance_id = ? ORDER BY registered_at", UUID::class.java, entranceId))
            .containsExactly(owner, holderOfUse)

        // the owner's decision: the manager, running the assembly, records the written authorisation another owner handed them
        val onPaper = owner(entranceId)
        val recorded = signedIn(manager) { created(proxies, withDocument.replace("$owner", "$onPaper")) }
        assertThat(recorded.get("principalPartyId").asText()).isEqualTo(onPaper.toString())
        assertThat(recorded.get("recordedBy").asText()).isEqualTo(manager.toString())
        assertThat(jdbc.queryForMap("SELECT recorded_by, authorisation_hash, login_subject FROM assembly.proxy WHERE principal_party_id = ?", onPaper))
            .containsEntry("recorded_by", manager).containsEntry("authorisation_hash", document).containsEntry("login_subject", "login-$manager")
        // nobody records an authorisation naming themself as the proxy
        signedIn(manager) {
            mvc.perform(post(proxies).contentType(MediaType.APPLICATION_JSON).content(withDocument.replace("$owner", "${owner(entranceId)}").replace("$agent", "$manager")))
                .andExpect(status().isBadRequest)
        }
        // the table holds both halves: in person no document, recorded always one, and always somebody who registered it
        for (broken in listOf("authorisation_hash = '$document'", "recorded_by = NULL")) {
            assertThatThrownBy { jdbc.update("UPDATE assembly.proxy SET $broken WHERE principal_party_id = ?", owner) }
                .isInstanceOf(DataIntegrityViolationException::class.java).hasMessageContaining("proxy_recorded_with_its_authorisation")
        }
        assertThatThrownBy { jdbc.update("UPDATE assembly.proxy SET authorisation_hash = NULL WHERE principal_party_id = ?", onPaper) }
            .isInstanceOf(DataIntegrityViolationException::class.java).hasMessageContaining("proxy_recorded_with_its_authorisation")
        // a principal with no title is not recorded either, document or not
        signedIn(manager) { mvc.perform(post(proxies).contentType(MediaType.APPLICATION_JSON).content(withDocument.replace("$owner", "$stranger"))).andExpect(status().isBadRequest) }
        assertThat(jdbc.queryForMap("SELECT recorded_by, authorisation_hash FROM assembly.proxy WHERE principal_party_id = ?", owner))
            .containsEntry("recorded_by", owner).containsEntry("authorisation_hash", null)
    }

    @Test
    fun `PM-GA-010 a registration past the limit for one agent is a 409 showing the limit, and nothing more is stored`() {
        val proxies = assembly(entrance())
        val entranceId = entranceOf(proxies)
        val agent = owner(entranceId)
        val limit = constantOn("GA_PROXY_MAX_PRINCIPALS", toSofiaDate(meets)).value.toInt()
        repeat(limit) { appoint(proxies, owner(entranceId), agent).andExpect(status().isCreated) }
        val refusal = json.readTree(appoint(proxies, owner(entranceId), agent).andExpect(status().isConflict).andReturn().response.contentAsString)
        assertThat(refusal.get("limit").asInt()).isEqualTo(limit)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM assembly.proxy WHERE agent_party_id = ?", Int::class.java, agent)).isEqualTo(limit)

        // through another entrance's path this assembly's proxies are neither listed nor added to
        val other = entrance()
        val elsewhere = proxies.replace(Regex("entrances/[^/]+"), "entrances/$other")
        mvc.perform(get(elsewhere)).andExpect(status().isNotFound)
        appoint(elsewhere, owner(other), owner(other)).andExpect(status().isNotFound)
    }
}
