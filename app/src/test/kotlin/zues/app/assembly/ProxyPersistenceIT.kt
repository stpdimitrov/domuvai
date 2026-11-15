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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import zues.app.identity_org.Logins
import zues.app.registry.BookHouseholdsAdapter
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

    /** An entrance or a party, registered as nobody — sign-in switched off — whoever the test has signed in: registering them is the administrator's (AUTH-05e). */
    private fun registered(path: String, body: String): JsonNode {
        val before = signedInAs
        signedInAs = null
        try { return created(path, body) } finally { signedInAs = before }
    }

    private fun entrance() = UUID.fromString(
        registered("/api/registry/entrances", """{"address":"ул. Раковски 1","label":"А","managementForm":"GA"}""").get("entranceId").asText(),
    )

    private val aside by lazy { entrance() }   // BOOK-02e: a party is registered in an entrance; one in no book here is registered aside
    private fun party(entranceId: UUID = aside) = UUID.fromString(registered("/api/registry/parties", """{"entranceId":"$entranceId","fullName":"Иван Петров"}""").get("partyId").asText())

    @Autowired lateinit var logins: Logins
    private val realm = "https://id.example.test/realms/${UUID.randomUUID()}"

    /** Whose requests these are: every request below carries this login's token, until another person is signed in. */
    private var signedInAs: String? = null

    /** A request, under [key] — a fresh one for a proxy unless the test replays one; null sends none (PM-SYS-014). */
    private fun post(path: String, key: String? = if (path.endsWith("/proxies")) UUID.randomUUID().toString() else null) =
        MockMvcRequestBuilders.post(path).also { request -> key?.let { request.header("Idempotency-Key", it) } }.also { request -> signedInAs?.let { request.with(jwt().jwt { token -> token.subject(it).issuer(realm) }) } }

    /** A party with a login of its own, tied to it. */
    private fun person(entranceId: UUID = aside): UUID = party(entranceId).also { logins.tie(realm, "login-$it", it) }

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
    private fun owner(entranceId: UUID): UUID = person(entranceId).also {
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
    private fun appoint(proxies: String, principal: UUID, agent: UUID, rest: String = """"agentKind":"OWNER","scope":"WHOLE_AGENDA","form":"WRITTEN"""", key: String? = UUID.randomUUID().toString()) =
        signedIn(principal) { mvc.perform(post(proxies, key).contentType(MediaType.APPLICATION_JSON).content(appointing(agent, rest))) }

    private fun proxyIds(result: org.springframework.test.web.servlet.ResultActions) = json.readTree(result.andExpect(status().isCreated).andReturn().response.contentAsString).get("id").asText()

    private fun proxyRows(entranceId: UUID) = jdbc.queryForList("SELECT id::text, principal_party_id, agent_party_id, request_key_id FROM assembly.proxy WHERE entrance_id = ? ORDER BY id", entranceId)

    private fun keptKeys(entranceId: UUID) = jdbc.queryForObject("SELECT count(*) FROM assembly.vote_request_key WHERE entrance_id = ?", Int::class.java, entranceId)

    private fun entranceOf(proxies: String) = UUID.fromString(Regex("entrances/([^/]+)").find(proxies)!!.groupValues[1])

    private fun householdChecks(entranceId: UUID): List<List<Any?>> =
        jdbc.queryForList("SELECT actor, purpose, book_date, outcome, rule_id FROM registry.book_access WHERE entrance_id = ? AND kind = 'HOUSEHOLD_CHECK' ORDER BY at, id", entranceId)
            .map { it.values.toList() }

    private fun proxiesOf(entranceId: UUID) = jdbc.queryForObject("SELECT count(*) FROM assembly.proxy WHERE entrance_id = ?", Int::class.java, entranceId)

    private val aHouseholdMember = """"agentKind":"HOUSEHOLD_MEMBER","scope":"WHOLE_AGENDA","form":"WRITTEN""""

    /** Somebody the book has living in [unit], flagged a child under six or not. */
    private fun living(entranceId: UUID, unit: UUID, party: UUID = person(entranceId), child: Boolean = false) = party.also {
        jdbc.update(
            sofiaDated("INSERT INTO registry.household_member (id, entrance_id, unit_id, party_id, is_child_under_6, valid_from, valid_to) VALUES (gen_random_uuid(), ?, ?, ?, ?, current_date - 100, NULL)"),
            entranceId, unit, it, child,
        )
    }

    @Test
    fun `PM-SEC-004 a household check's entry survives the 400 that rolls the registration back, the export lists it, and it cannot be changed or removed`() {
        val proxies = assembly(entrance())
        val entranceId = entranceOf(proxies)
        val manager = jdbc.queryForObject("SELECT party_id FROM identity_org.management_mandate WHERE entrance_id = ?", UUID::class.java, entranceId)!!
        val principal = owner(entranceId)
        val unit = jdbc.queryForObject("SELECT id FROM registry.unit WHERE entrance_id = ?", UUID::class.java, entranceId)!!
        living(entranceId, unit, principal)
        val today = java.sql.Date.valueOf(toSofiaDate(Instant.now()))

        appoint(proxies, principal, person(), aHouseholdMember).andExpect(status().isBadRequest)
        assertThat(proxiesOf(entranceId)).isZero()
        val entry = listOf(principal, BookHouseholdsAdapter.PURPOSE, today, "SERVED", "PM-BOOK-006")
        assertThat(householdChecks(entranceId)).containsExactly(entry)

        val refused = appoint(proxies, principal, living(entranceId, unit, child = true), aHouseholdMember).andExpect(status().isBadRequest).andReturn().response.contentAsString
        assertThat(refused).contains("child under six")
        assertThat(proxiesOf(entranceId)).isZero()
        assertThat(householdChecks(entranceId)).containsExactly(entry, entry)

        appoint(proxies, principal, living(entranceId, unit), aHouseholdMember).andExpect(status().isCreated)
        assertThat(proxiesOf(entranceId)).isOne()
        assertThat(householdChecks(entranceId)).containsExactly(entry, entry, entry)

        // a proxy of another kind asks the book nothing about who lives where
        appoint(proxies, owner(entranceId), party(), """"agentKind":"THIRD_PARTY","scope":"WHOLE_AGENDA","form":"NOTARISED"""").andExpect(status().isCreated)
        assertThat(householdChecks(entranceId)).hasSize(3)

        mvc.perform(get("/api/registry/entrances/$entranceId/book/access-log").param("purpose", "проверка на КЗЛД").with(jwt().jwt { it.subject("login-$manager").issuer(realm) }))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[?(@.kind == 'HOUSEHOLD_CHECK')].actor").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.equalTo("$principal"))))

        jdbc.update("UPDATE registry.book_access SET purpose = 'нищо' WHERE entrance_id = ?", entranceId)
        jdbc.update("DELETE FROM registry.book_access WHERE entrance_id = ?", entranceId)
        assertThat(householdChecks(entranceId)).containsExactly(entry, entry, entry)
    }

    @Test
    fun `PM-BOOK-006 an owner running an assembly on a petition records nothing the book does not bear out, and hears no word of the flag`() {
        val entranceId = entrance()
        val principal = owner(entranceId)
        val unit = jdbc.queryForObject("SELECT id FROM registry.unit WHERE entrance_id = ?", UUID::class.java, entranceId)!!
        living(entranceId, unit, principal)
        val recorder = owner(entranceId)
        val petitions = "/api/assembly/entrances/$entranceId/petitions"
        val meeting = """{"scheduledAt":"$meets","place":"фоайето на вх. А","mode":"IN_PERSON","demandUnmet":"Искането е връчено на управителя; събрание не е свикано."}"""
        val convened = signedIn(recorder) {
            val petition = created(petitions, """{"subject":"Ремонт на покрива"}""").get("id").asText()
            created("$petitions/$petition/assembly", meeting).get("assemblyId").asText()
        }
        val proxies = "/api/assembly/entrances/$entranceId/assemblies/$convened/proxies"
        val written = { agent: UUID -> """{"principalPartyId":"$principal","authorisationHash":"${"5d".repeat(32)}","agentPartyId":"$agent",$aHouseholdMember}""" }
        val record = { agent: UUID -> signedIn(recorder) { mvc.perform(post(proxies).contentType(MediaType.APPLICATION_JSON).content(written(agent))) } }

        val child = record(living(entranceId, unit, child = true)).andExpect(status().isBadRequest).andReturn().response.contentAsString
        val stranger = record(person()).andExpect(status().isBadRequest).andReturn().response.contentAsString

        assertThat(child).contains("does not bear that out").doesNotContain("child").isEqualTo(stranger)
        record(living(entranceId, unit)).andExpect(status().isCreated)
        val today = java.sql.Date.valueOf(toSofiaDate(Instant.now()))
        assertThat(householdChecks(entranceId).toSet()).containsExactly(listOf(recorder, BookHouseholdsAdapter.PURPOSE, today, "REFUSED", "PM-BOOK-006"))
        assertThat(householdChecks(entranceId)).hasSize(3)
    }

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
        val former = person(entranceId).also {
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
        val atHome = person(entranceId).also { jdbc.update(sofiaDated(resident), entranceId, unit, it) }
        appoint(proxies, owner, atHome, member).andExpect(status().isBadRequest)                               // the owner is not in the book as living there
        jdbc.update(sofiaDated(resident), entranceId, unit, owner)
        val movedOut = person(entranceId).also {
            jdbc.update(sofiaDated(resident.replace("current_date - 100, NULL", "current_date - 100, current_date - 1")), entranceId, unit, it)
        }
        appoint(proxies, owner, movedOut, member).andExpect(status().isBadRequest)
        // a resident of another unit of the entrance is not of this household; an owner next door is not "another owner" here
        val acrossTheLanding = person(entranceId).also { jdbc.update(sofiaDated(resident), entranceId, otherUnit, it) }
        appoint(proxies, owner, acrossTheLanding, member).andExpect(status().isBadRequest)
        appoint(proxies, owner, owner(entrance())).andExpect(status().isBadRequest)
        // a child under six of this household is no adult: the book's flag, through the registry's port
        val child = person(entranceId).also { jdbc.update(sofiaDated(resident.replace("false,", "true,")), entranceId, unit, it) }
        val refused = appoint(proxies, owner, child, member).andExpect(status().isBadRequest).andReturn().response.contentAsString
        assertThat(refused).contains("child under six")
        // somebody whose title begins tomorrow is not an owner today: neither appoints nor is "another owner"
        val buyer = person(entranceId).also {
            jdbc.update(sofiaDated("INSERT INTO registry.title (id, entrance_id, unit_id, party_id, title_role, share, valid_from) VALUES (gen_random_uuid(), ?, ?, ?, 'OWN', 0.000001, current_date + 1)"), entranceId, unit, it)
        }
        appoint(proxies, buyer, agent).andExpect(status().isForbidden)
        appoint(proxies, owner, buyer).andExpect(status().isBadRequest)
        // an owner naming another principal is refused — they do not run the assembly — and it is not made the caller's own
        signedIn(owner) { mvc.perform(post(proxies).contentType(MediaType.APPLICATION_JSON).content(naming.replace("$owner", "$agent"))).andExpect(status().isForbidden) }
        appoint(proxies, owner, atHome, member).andExpect(status().isCreated)
        // a holder of a right of use appoints too, and another owner represents them
        val holderOfUse = person(entranceId).also {
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

    @Test
    fun `PM-SYS-014 replaying a queued proxy twice registers it once - the same key and request answer with the proxy first made, also once the assembly takes no more proxies`() {
        val proxies = assembly(entrance())
        val entranceId = entranceOf(proxies)
        val principal = owner(entranceId)
        val agent = owner(entranceId)
        val queued = UUID.randomUUID().toString()
        val first = proxyIds(appoint(proxies, principal, agent, key = queued))
        repeat(2) { assertThat(proxyIds(appoint(proxies, principal, agent, key = queued))).isEqualTo(first) }          // the queue replayed twice: one proxy
        assertThat(proxyRows(entranceId).map { it["id"] }).containsExactly(first)
        assertThat(keptKeys(entranceId)).isEqualTo(1)
        appoint(proxies, principal, agent).andExpect(status().isConflict)                                              // another key: this principal has a proxy
        val manager = jdbc.queryForObject("SELECT party_id FROM identity_org.management_mandate WHERE entrance_id = ?", UUID::class.java, entranceId)!!
        signedIn(manager) { created(proxies.removeSuffix("/proxies") + "/notice-posting", """{"postedAt":"${Instant.now()}","coSignatoryPartyId":"$agent","photoHash":"${"3f".repeat(32)}"}""") }
        jdbc.update("UPDATE assembly.assembly SET status = 'OPEN' WHERE id = ?::uuid", proxies.substringAfter("/assemblies/").substringBefore("/"))
        assertThat(proxyIds(appoint(proxies, principal, agent, key = queued))).isEqualTo(first)                        // an assembly under way takes no more, yet the replay is answered
        appoint(proxies, owner(entranceId), agent).andExpect(status().isConflict)
        assertThat(proxyRows(entranceId)).hasSize(1)

        // recorded from a written authorisation by whoever runs the assembly: the same, and the document is part of the request
        val other = assembly(entrance())
        val otherEntrance = entranceOf(other)
        val onPaper = owner(otherEntrance)
        val recorder = jdbc.queryForObject("SELECT party_id FROM identity_org.management_mandate WHERE entrance_id = ?", UUID::class.java, otherEntrance)!!
        val written = """{"principalPartyId":"$onPaper","authorisationHash":"${"5d".repeat(32)}","agentPartyId":"${owner(otherEntrance)}","agentKind":"OWNER","scope":"WHOLE_AGENDA","form":"WRITTEN"}"""
        val sent = UUID.randomUUID().toString()
        val recordedAs = { body: String -> signedIn(recorder) { mvc.perform(post(other, sent).contentType(MediaType.APPLICATION_JSON).content(body)) } }
        val kept = proxyIds(recordedAs(written))
        assertThat(proxyIds(recordedAs(written))).isEqualTo(kept)
        recordedAs(written.replace("5d".repeat(32), "6e".repeat(32))).andExpect(status().isConflict)                  // another document: another request
        assertThat(proxyRows(otherEntrance)).hasSize(1)
    }

    @Test
    fun `PM-SYS-014 a proxy's key is refused for another request or operation, and a proxy without an Idempotency-Key is a 400`() {
        val proxies = assembly(entrance())
        val entranceId = entranceOf(proxies)
        val principal = owner(entranceId)
        val agent = owner(entranceId)
        appoint(proxies, principal, agent, key = null).andExpect(status().isBadRequest)
        appoint(proxies, principal, agent, key = "  ").andExpect(status().isBadRequest)
        val k = UUID.randomUUID().toString()
        jdbc.update("INSERT INTO assembly.vote_request_key (id, entrance_id, idempotency_key, operation, request_hash, kept_at) VALUES (gen_random_uuid(), ?, ?, 'RECORD_VOTE', 'x', now())", entranceId, k)
        appoint(proxies, principal, agent, key = k).andExpect(status().isConflict)                                      // k was kept for a vote: another operation
        assertThat(proxyRows(entranceId)).isEmpty()
        assertThat(keptKeys(entranceId)).isEqualTo(1)                                                                   // a refused proxy keeps no key
        appoint(proxies, principal, agent, """"agentKind":"OWNER","scope":"WHOLE_AGENDA","form":"WRITTEN"""", key = "bad").andExpect(status().isCreated)
        appoint(proxies, principal, agent, """"agentKind":"THIRD_PARTY","scope":"WHOLE_AGENDA","form":"WRITTEN"""", key = "bad").andExpect(status().isConflict)    // another kind
        appoint(proxies, principal, agent, """"agentKind":"OWNER","scope":"LISTED_ITEMS","items":[1],"form":"WRITTEN"""", key = "bad").andExpect(status().isConflict)
        appoint(proxies, principal, owner(entranceId), key = "bad").andExpect(status().isConflict)                       // another agent
        appoint(proxies, owner(entranceId), agent, key = "bad").andExpect(status().isConflict)                           // another principal
        assertThat(proxyRows(entranceId)).hasSize(1)
        // a registration the table refuses (an agent nobody registered) is rolled back with its key: nothing is kept
        val before = keptKeys(entranceId)
        appoint(proxies, owner(entranceId), UUID.randomUUID(), """"agentKind":"THIRD_PARTY","scope":"WHOLE_AGENDA","form":"WRITTEN"""", key = UUID.randomUUID().toString()).andExpect(status().isBadRequest)
        assertThat(keptKeys(entranceId)).isEqualTo(before)
    }

    @Test
    fun `PM-GA-010 a replay of the proxy that reached the limit is answered with it, not refused as past the limit, while a new key for one more is still refused showing the limit`() {
        val proxies = assembly(entrance())
        val entranceId = entranceOf(proxies)
        val agent = owner(entranceId)
        val limit = constantOn("GA_PROXY_MAX_PRINCIPALS", toSofiaDate(meets)).value.toInt()
        val last = owner(entranceId)
        repeat(limit - 1) { appoint(proxies, owner(entranceId), agent).andExpect(status().isCreated) }
        val queued = UUID.randomUUID().toString()
        val reached = proxyIds(appoint(proxies, last, agent, key = queued))
        assertThat(proxyIds(appoint(proxies, last, agent, key = queued))).isEqualTo(reached)                            // the agent is at the limit: the replay is not one more
        val refusal = json.readTree(appoint(proxies, owner(entranceId), agent).andExpect(status().isConflict).andReturn().response.contentAsString)
        assertThat(refusal.get("limit").asInt()).isEqualTo(limit)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM assembly.proxy WHERE agent_party_id = ?", Int::class.java, agent)).isEqualTo(limit)
    }

    @Test
    fun `PM-VOTE-014 a replayed proxy adds no row and edits none - its row names the key, which is never changed or removed`() {
        val proxies = assembly(entrance())
        val entranceId = entranceOf(proxies)
        val principal = owner(entranceId)
        val queued = UUID.randomUUID().toString()
        val agent = owner(entranceId)
        repeat(2) { proxyIds(appoint(proxies, principal, agent, key = queued)) }
        val kept = jdbc.queryForObject("SELECT id FROM assembly.vote_request_key WHERE entrance_id = ? AND idempotency_key = ?", UUID::class.java, entranceId, queued)
        assertThat(jdbc.queryForObject("SELECT operation FROM assembly.vote_request_key WHERE id = ?", String::class.java, kept)).isEqualTo("REGISTER_PROXY")
        val row = proxyRows(entranceId).single()
        assertThat(row["request_key_id"]).isEqualTo(kept)
        assertThat(jdbc.update("UPDATE assembly.vote_request_key SET request_hash = 'x' WHERE id = ?", kept)).isZero()
        assertThat(jdbc.update("DELETE FROM assembly.vote_request_key WHERE id = ?", kept)).isZero()
        assertThat(proxyRows(entranceId).single()).isEqualTo(row)                                                       // the replay changed nothing
    }

    @Test
    fun `PM-SEC-001 a replayed proxy is answered only to whoever may register it - the policy is asked before the key is looked up`() {
        val proxies = assembly(entrance())
        val entranceId = entranceOf(proxies)
        val principal = owner(entranceId)
        val agent = owner(entranceId)
        val queued = UUID.randomUUID().toString()
        proxyIds(appoint(proxies, principal, agent, key = queued))
        val naming = """{"principalPartyId":"$principal","authorisationHash":"${"5d".repeat(32)}","agentPartyId":"$agent","agentKind":"OWNER","scope":"WHOLE_AGENDA","form":"WRITTEN"}"""
        for (who in listOf(agent, owner(entranceId), person())) {                                                       // an owner naming another principal runs no assembly
            signedIn(who) { mvc.perform(post(proxies, queued).contentType(MediaType.APPLICATION_JSON).content(naming)).andExpect(status().isForbidden) }
        }
        signedIn(person()) { mvc.perform(post(proxies, queued).contentType(MediaType.APPLICATION_JSON).content(appointing(agent))).andExpect(status().isForbidden) }   // a stranger appointing in person
        signedIn(null) { mvc.perform(post(proxies, queued).contentType(MediaType.APPLICATION_JSON).content(appointing(agent))).andExpect(status().isForbidden) }       // nobody signed in
        assertThat(proxyRows(entranceId)).hasSize(1)
    }
}
