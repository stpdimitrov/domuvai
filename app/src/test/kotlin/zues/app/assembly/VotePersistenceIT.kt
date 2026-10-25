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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import zues.app.identity_org.Logins
import zues.kernel.toSofiaDate
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Votes against real PostgreSQL and the real registry, through the HTTP layer with real sign-ins: an opened sitting,
 * owners present in person and by proxy, a co-owned unit, a correction, and the table that refuses to be edited.
 * Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class VotePersistenceIT {

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
    @Autowired lateinit var logins: Logins

    private val realm = "https://id.example.test/realms/${UUID.randomUUID()}"
    private var signedInAs: String? = null

    private fun post(path: String, body: String) = MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(body)
        .also { request -> signedInAs?.let { request.with(jwt().jwt { token -> token.subject(it).issuer(realm) }) } }

    private fun created(path: String, body: String): JsonNode =
        json.readTree(mvc.perform(post(path, body)).andExpect(status().isCreated).andReturn().response.contentAsString)

    private fun <T> signedIn(party: UUID, act: () -> T): T {
        val before = signedInAs
        signedInAs = "login-$party"
        try { return act() } finally { signedInAs = before }
    }

    private fun person(): UUID = UUID.fromString(created("/api/registry/parties", """{"fullName":"Иван Петров"}""").get("partyId").asText())
        .also { logins.tie(realm, "login-$it", it) }

    /** An entrance of one unit of 100% — owned a half by the first owner, a quarter by each of the next two. */
    private class Meeting(val entranceId: UUID, val base: String, val item: String, val manager: UUID, val owners: List<UUID>, val unit: UUID)

    /** Convened by the manager, noticed a month ahead, then made to meet today; the second owner's proxy names the first, who is present; the sitting opened. */
    private fun opened(): Meeting {
        val entranceId = UUID.fromString(created("/api/registry/entrances", """{"address":"ул. Раковски 1","label":"А","managementForm":"GA"}""").get("entranceId").asText())
        val unit = UUID.fromString(
            created("/api/registry/entrances/$entranceId/units", """{"units":[{"designation":"ап. 1","unitType":"FLAT","idealParts":"100.0000","separateEntrance":false}]}""")
                .get("unitIds")[0].asText(),
        )
        val owners = listOf("0.5", "0.25", "0.25").map { share ->
            person().also { created("/api/registry/entrances/$entranceId/units/$unit/titles", """{"partyId":"$it","titleRole":"OWN","share":"$share","validFrom":"2025-01-01"}""") }
        }
        val manager = person()
        jdbc.update(
            sofiaDated("INSERT INTO identity_org.management_mandate (id, entrance_id, body, party_id, valid_from, valid_to) VALUES (gen_random_uuid(), ?, 'BM', ?, current_date - 30, current_date + 300)"),
            entranceId, manager,
        )
        val meets = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
        val base = "/api/assembly/entrances/$entranceId/assemblies"
        val id = signedIn(manager) {
            created(base, """{"convenedAs":"BM","scheduledAt":"$meets","place":"фоайето","mode":"IN_PERSON","urgent":false}""").get("id").asText()
        }
        val of = "$base/$id"
        val item = signedIn(manager) {
            created("$of/agenda", """{"text":"Ремонт на покрива","itemType":"GENERAL"}""").get("id").asText()
                .also { created("$of/notice-posting", """{"postedAt":"${Instant.now()}","coSignatoryPartyId":"${owners[0]}","photoHash":"${"3f".repeat(32)}"}""") }
        }
        jdbc.update("UPDATE assembly.assembly SET scheduled_at = now() WHERE id = ?::uuid", id)
        signedIn(owners[1]) { created("$of/proxies", """{"agentPartyId":"${owners[0]}","agentKind":"OWNER","scope":"WHOLE_AGENDA","form":"WRITTEN"}""") }
        signedIn(manager) {
            created("$of/attendance", """{"attendeePartyId":"${owners[0]}","channel":"IN_PERSON"}""")
            assertThat(created("$of/sittings", "{}").get("quorumMet").asBoolean()).isTrue()                  // 75% present
        }
        return Meeting(entranceId, of, item, manager, owners, unit)
    }

    private fun vote(m: Meeting, owner: UUID, choice: String, correction: String? = null, item: String = m.item): ResultActions =
        mvc.perform(post("${m.base}/agenda/$item/votes", """{"ownerPartyId":"$owner","choice":"$choice"${correction?.let { ""","correction":"$it"""" }.orEmpty()}}"""))

    private fun rows(m: Meeting) = jdbc.queryForList("SELECT id, party_id, weight, choice, channel, reverses, correction FROM assembly.vote WHERE agenda_item_id = ?::uuid ORDER BY cast_at, id", m.item)

    @Test
    fun `PM-ORG-005 a vote weighs the owner's title share of the unit's ideal parts - in person or by proxy, once, and only for an owner present`() {
        val m = opened()
        val (ana, boris, vera) = m.owners
        signedIn(m.manager) {
            vote(m, ana, "FOR").andExpect(status().isCreated)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].weight").value("50")).andExpect(jsonPath("$[0].channel").value("IN_PERSON"))
                .andExpect(jsonPath("$[0].unitId").value(m.unit.toString())).andExpect(jsonPath("$[0].sessionNo").value(1))
                .andExpect(jsonPath("$[0].recordedBy").value(m.manager.toString()))
            vote(m, boris, "AGAINST").andExpect(status().isCreated)
                .andExpect(jsonPath("$[0].weight").value("25")).andExpect(jsonPath("$[0].channel").value("PROXY"))   // brought by Ana's presence
            vote(m, vera, "FOR").andExpect(status().isBadRequest)                                                    // not present: no vote
            vote(m, ana, "AGAINST").andExpect(status().isConflict)                                                    // once — a second is a correction
            vote(m, ana, "MAYBE").andExpect(status().isBadRequest)
            // Vera arrives, and votes against: half the unit for, half against — they cancel out
            created("${m.base}/attendance", """{"attendeePartyId":"$vera","channel":"IN_PERSON"}""")
            vote(m, vera, "AGAINST").andExpect(status().isCreated).andExpect(jsonPath("$[0].weight").value("25"))
        }
        // what was cast is the unit's ideal parts, each owner counted once by their share
        fun cast(choice: String?) = rows(m).filter { choice == null || it["choice"] == choice }.sumOf { it["weight"] as java.math.BigDecimal }.stripTrailingZeros().toPlainString()
        assertThat(listOf(cast("FOR"), cast("AGAINST"), cast(null))).containsExactly("50", "50", "100")
        assertThatThrownBy {
            jdbc.update(
                "INSERT INTO assembly.vote (id, entrance_id, assembly_id, agenda_item_id, session_no, party_id, unit_id, weight, choice, channel, cast_at, recorded_by, login_issuer, login_subject) " +
                    "SELECT gen_random_uuid(), entrance_id, assembly_id, agenda_item_id, session_no, party_id, unit_id, weight, 'AGAINST', channel, now(), recorded_by, login_issuer, login_subject FROM assembly.vote WHERE party_id = ?",
                ana,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java).hasMessageContaining("vote_first_once")
    }

    @Test
    fun `PM-VOTE-014 a vote is never edited - a correction is a new row that reverses it and says why, and the table refuses an update or a delete`() {
        val m = opened()
        val ana = m.owners[0]
        val first = signedIn(m.manager) { json.readTree(vote(m, ana, "FOR").andExpect(status().isCreated).andReturn().response.contentAsString)[0] }
        signedIn(m.manager) {
            vote(m, ana, "FOR", correction = "  ").andExpect(status().isConflict)                                      // a correction says why
            vote(m, m.owners[1], "FOR", correction = "грешка").andExpect(status().isBadRequest)                       // nothing of Boris's to correct
            vote(m, ana, "AGAINST", correction = "вдигна ръка против, записано погрешно").andExpect(status().isCreated)
                .andExpect(jsonPath("$[0].reverses").value(first.get("id").asText()))
                .andExpect(jsonPath("$[0].correction").value("вдигна ръка против, записано погрешно"))
                .andExpect(jsonPath("$[0].weight").value("50"))
            vote(m, ana, "ABSTAIN", correction = "отново поправено").andExpect(status().isCreated)                    // the correction is reversed in its turn
        }
        val kept = rows(m)
        assertThat(kept.map { it["choice"] }).containsExactly("FOR", "AGAINST", "ABSTAIN")
        assertThat(listOf(kept[1]["reverses"], kept[2]["reverses"])).containsExactly(UUID.fromString(first.get("id").asText()), kept[1]["id"])
        // nothing is edited or removed, whoever asks the database
        assertThat(jdbc.update("UPDATE assembly.vote SET choice = 'FOR' WHERE party_id = ?", ana)).isZero()
        assertThat(jdbc.update("DELETE FROM assembly.vote WHERE party_id = ?", ana)).isZero()
        assertThat(rows(m)).isEqualTo(kept)
        // a row is reversed once; a correction names what it reverses, and a first vote names no correction
        val copy = "INSERT INTO assembly.vote (id, entrance_id, assembly_id, agenda_item_id, session_no, party_id, unit_id, weight, choice, channel, cast_at, reverses, correction, recorded_by, login_issuer, login_subject) " +
            "SELECT gen_random_uuid(), entrance_id, assembly_id, agenda_item_id, session_no, party_id, unit_id, weight, 'FOR', channel, now(), %s, %s, recorded_by, login_issuer, login_subject FROM assembly.vote WHERE id = ?::uuid"
        assertThatThrownBy { jdbc.update(copy.format("?::uuid", "'двойно'"), first.get("id").asText(), first.get("id").asText()) }
            .isInstanceOf(DataIntegrityViolationException::class.java).hasMessageContaining("vote_reversed_once")
        assertThatThrownBy { jdbc.update(copy.format("NULL", "'без обръщане'"), first.get("id").asText()) }
            .isInstanceOf(DataIntegrityViolationException::class.java).hasMessageContaining("vote_correction_says_why")
    }

    @Test
    fun `PM-VOTE-001 a vote goes on an item of this assembly's agenda, at its opened sitting - an item of another, or none, is a 404, and before the sitting opens a 409`() {
        val m = opened()
        val other = opened()
        signedIn(m.manager) {
            // an item of another assembly of the same entrance, convened by the same manager
            val later = Instant.now().plus(60, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
            val sibling = created(m.base.substringBeforeLast("/"), """{"convenedAs":"BM","scheduledAt":"$later","place":"двора","mode":"IN_PERSON","urgent":false}""").get("id").asText()
            val itsItem = created("${m.base.substringBeforeLast("/")}/$sibling/agenda", """{"text":"Друго","itemType":"GENERAL"}""").get("id").asText()
            vote(m, m.owners[0], "FOR", item = itsItem).andExpect(status().isNotFound)
            vote(m, m.owners[0], "FOR", item = other.item).andExpect(status().isNotFound)
            vote(m, m.owners[0], "FOR", item = UUID.randomUUID().toString()).andExpect(status().isNotFound)
        }
        // an assembly whose sitting has not opened takes no vote
        jdbc.update("UPDATE assembly.assembly SET status = 'NOTICED' WHERE id = ?::uuid", m.base.substringAfterLast("/"))
        signedIn(m.manager) { vote(m, m.owners[0], "FOR").andExpect(status().isConflict) }
        assertThat(rows(m)).isEmpty()
        // the item named carries the majority it was bound to on the agenda
        val bound = json.readTree(mvc.perform(get(m.base)).andReturn().response.contentAsString).get("agenda")[0].get("majorityRuleId").asText()
        assertThat(bound).startsWith("GENERAL@")
    }

    @Test
    fun `PM-SEC-001 a vote is recorded by whoever runs the assembly - an owner, another office and a stranger are a 403 naming the rule, and nothing is recorded`() {
        val m = opened()
        val controller = person()
        jdbc.update(
            sofiaDated("INSERT INTO identity_org.management_mandate (id, entrance_id, body, party_id, valid_from, valid_to) VALUES (gen_random_uuid(), ?, 'CTL', ?, current_date - 30, current_date + 300)"),
            m.entranceId, controller,
        )
        for (who in listOf(m.owners[0], m.owners[1], controller, person())) {
            signedIn(who) { vote(m, m.owners[0], "FOR").andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-SEC-001")) }
        }
        vote(m, m.owners[0], "FOR").andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-SEC-001"))   // nobody signed in records nothing
        assertThat(rows(m)).isEmpty()
    }

    private fun close(m: Meeting): ResultActions = mvc.perform(post("${m.base}/agenda/${m.item}/decision", ""))

    private fun decisions(m: Meeting) = jdbc.queryForList("SELECT * FROM assembly.decision WHERE agenda_item_id = ?::uuid", m.item)

    @Test
    fun `PM-VOTE-016 closing an item stores the majority applied, the tally of the votes in effect, the sitting and the status - reproducible from its basis`() {
        val m = opened()
        val (ana, boris) = m.owners
        signedIn(m.manager) {
            vote(m, ana, "FOR").andExpect(status().isCreated)
            vote(m, boris, "FOR").andExpect(status().isCreated)
            vote(m, boris, "AGAINST", correction = "пълномощникът гласува против").andExpect(status().isCreated)    // the reversed FOR is not counted
            close(m).andExpect(status().isCreated)
                .andExpect(jsonPath("$.majorityRuleId").value("GENERAL@2009-01-01")).andExpect(jsonPath("$.denominator").value("REPRESENTED"))
                .andExpect(jsonPath("$.comparison").value("MORE_THAN")).andExpect(jsonPath("$.thresholdPct").value("50"))
                .andExpect(jsonPath("$.represented").value("75")).andExpect(jsonPath("$.sessionNo").value(1))
                .andExpect(jsonPath("$.tallyFor").value("50")).andExpect(jsonPath("$.tallyAgainst").value("25")).andExpect(jsonPath("$.tallyAbstain").value("0"))
                .andExpect(jsonPath("$.result").value("FOR 66.67% of represented (50.00% of total, 75.00% represented); threshold more than 50% of represented (GENERAL@2009-01-01) — PASSED"))
                .andExpect(jsonPath("$.outcome").value("PASSED")).andExpect(jsonPath("$.status").value("PROVISIONAL"))
                .andExpect(jsonPath("$.closedBy").value(m.manager.toString()))
        }
        val stored = decisions(m).single()
        val basis = stored["basis"] as String
        assertThat(stored["basis_hash"]).isEqualTo(java.security.MessageDigest.getInstance("SHA-256").digest(basis.toByteArray()).joinToString("") { "%02x".format(it) })
        assertThat(listOf(stored["law_version"], stored["engine_version"], (stored["session_no"] as Number).toInt())).containsExactly(zues.law.CATALOGUE_VERSION, zues.law.ENGINE_VERSION, 1)
        // every vote in effect, and only those, is in the basis by its id
        val inEffect = jdbc.queryForList("SELECT id FROM assembly.vote v WHERE agenda_item_id = ?::uuid AND NOT EXISTS (SELECT 1 FROM assembly.vote r WHERE r.reverses = v.id)", UUID::class.java, m.item)
        val counted = basis.lines().drop(1).filterNot { it.startsWith("present ") }
        assertThat(counted.map { it.substringAfterLast(" by ") }).containsExactlyInAnyOrderElementsOf(inEffect.map { it.toString() })
        assertThat(counted).hasSize(2).anyMatch { it.startsWith("$boris ${m.unit} 25 AGAINST") }
        assertThat(jdbc.update("UPDATE assembly.decision SET outcome = 'NOT_PASSED' WHERE agenda_item_id = ?::uuid", m.item)).isZero()
        assertThat(jdbc.update("DELETE FROM assembly.decision WHERE agenda_item_id = ?::uuid", m.item)).isZero()
        assertThat(decisions(m).single()["basis_hash"]).isEqualTo(stored["basis_hash"])
    }

    @Test
    fun `PM-VOTE-014 once an item is closed no vote on it is taken or corrected, and it is closed once`() {
        val m = opened()
        val (ana, boris) = m.owners
        signedIn(m.manager) {
            vote(m, ana, "AGAINST").andExpect(status().isCreated)
            close(m).andExpect(status().isCreated).andExpect(jsonPath("$.outcome").value("NOT_PASSED"))
            vote(m, boris, "FOR").andExpect(status().isConflict)
            vote(m, ana, "FOR", correction = "късно").andExpect(status().isConflict)
            close(m).andExpect(status().isConflict)
        }
        assertThat(rows(m)).hasSize(1)
        assertThat(decisions(m)).hasSize(1)
    }

    @Test
    fun `PM-VOTE-011 a late arrival's vote counts against the represented share re-weighed at the close - the opened sitting keeps the share it was weighed on`() {
        val m = opened()
        val vera = m.owners[2]
        signedIn(m.manager) {
            vote(m, m.owners[0], "FOR").andExpect(status().isCreated)
            created("${m.base}/attendance", """{"attendeePartyId":"$vera","channel":"IN_PERSON"}""")        // Vera arrives after the opening
            vote(m, vera, "FOR").andExpect(status().isCreated)
            close(m).andExpect(status().isCreated)
                .andExpect(jsonPath("$.represented").value("100")).andExpect(jsonPath("$.tallyFor").value("75"))
                .andExpect(jsonPath("$.result").value("FOR 75.00% of represented (75.00% of total, 100.00% represented); threshold more than 50% of represented (GENERAL@2009-01-01) — PASSED"))
        }
        val basis = decisions(m).single()["basis"] as String
        assertThat(basis.lines().first()).contains(", 100 represented at the close: ")
        assertThat(basis.lines().filter { it.startsWith("present ") }.map { it.split(" ")[1] }).containsExactlyInAnyOrderElementsOf(m.owners.map { it.toString() })
        assertThat(jdbc.queryForObject("SELECT represented FROM assembly.session WHERE assembly_id = ?::uuid", java.math.BigDecimal::class.java, m.base.substringAfterLast("/")))
            .isEqualByComparingTo("75")
    }

    @Test
    fun `PM-VOTE-011 a close whose represented share cannot be weighed is refused, as the opening would be - nothing is stored`() {
        val m = opened()
        signedIn(m.manager) {
            vote(m, m.owners[0], "FOR").andExpect(status().isCreated)
            created("/api/registry/entrances/${m.entranceId}/units/${m.unit}/titles", """{"partyId":"${person()}","titleRole":"USR","share":"1","validFrom":"2025-01-01"}""")
            close(m).andExpect(status().isConflict).andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("right of use")))
        }
        assertThat(decisions(m)).isEmpty()
    }

    @Test
    fun `PM-SEC-001 an item is closed by whoever runs the assembly - an owner, another office and a stranger are a 403 naming the rule`() {
        val m = opened()
        val controller = person()
        jdbc.update(
            sofiaDated("INSERT INTO identity_org.management_mandate (id, entrance_id, body, party_id, valid_from, valid_to) VALUES (gen_random_uuid(), ?, 'CTL', ?, current_date - 30, current_date + 300)"),
            m.entranceId, controller,
        )
        for (who in listOf(m.owners[0], controller, person())) {
            signedIn(who) { close(m).andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-SEC-001")) }
        }
        close(m).andExpect(status().isForbidden)
        assertThat(decisions(m)).isEmpty()
    }

    private fun read(path: String): ResultActions =
        mvc.perform(get(path).also { request -> signedInAs?.let { request.with(jwt().jwt { token -> token.subject(it).issuer(realm) }) } })

    private fun mandated(m: Meeting, body: String): UUID = person().also {
        jdbc.update(
            sofiaDated("INSERT INTO identity_org.management_mandate (id, entrance_id, body, party_id, valid_from, valid_to) VALUES (gen_random_uuid(), ?, ?, ?, current_date - 30, current_date + 300)"),
            m.entranceId, body, it,
        )
    }

    @Test
    fun `PM-VOTE-016 a closed item's decision is read back - the majority, the tally, the result line and the status, by an owner too - before the close a 404`() {
        val m = opened()
        signedIn(m.owners[2]) { read("${m.base}/agenda/${m.item}/decision").andExpect(status().isNotFound) }
        signedIn(m.manager) {
            vote(m, m.owners[0], "FOR").andExpect(status().isCreated)
            close(m).andExpect(status().isCreated)
        }
        val stored = decisions(m).single()
        signedIn(m.owners[2]) {
            read("${m.base}/agenda/${m.item}/decision").andExpect(status().isOk)
                .andExpect(jsonPath("$.id").value(stored["id"].toString())).andExpect(jsonPath("$.majorityRuleId").value("GENERAL@2009-01-01"))
                .andExpect(jsonPath("$.denominator").value("REPRESENTED")).andExpect(jsonPath("$.sessionNo").value(1))
                .andExpect(jsonPath("$.tallyFor").value("50")).andExpect(jsonPath("$.represented").value("75"))
                .andExpect(jsonPath("$.result").value("FOR 66.67% of represented (50.00% of total, 75.00% represented); threshold more than 50% of represented (GENERAL@2009-01-01) — PASSED"))
                .andExpect(jsonPath("$.status").value("PROVISIONAL")).andExpect(jsonPath("$.basisHash").value(stored["basis_hash"] as String))
                .andExpect(jsonPath("$.basis").doesNotExist())                                               // the basis names how each owner voted
            read("${m.base}/agenda/${UUID.randomUUID()}/decision").andExpect(status().isNotFound)
        }
        val elsewhere = opened()                                                                             // another entrance's closed item, asked through this one
        signedIn(elsewhere.manager) { close(elsewhere).andExpect(status().isCreated) }
        signedIn(m.owners[2]) { read("${m.base}/agenda/${elsewhere.item}/decision").andExpect(status().isNotFound) }
        signedIn(person()) { read("${m.base}/agenda/${m.item}/decision").andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-SEC-001")) }
    }

    private fun postProtocol(m: Meeting, on: String): ResultActions =
        mvc.perform(post("${m.base}/protocol-posting", """{"postedOn":"$on"}"""))

    @Test
    fun `PM-GA-023 the protocol's posting starts the countdown - entered once by whoever runs the assembly, never edited - and the decision reads PROVISIONAL with its final day`() {
        val m = opened()
        val today = toSofiaDate(Instant.now())
        signedIn(m.manager) {
            vote(m, m.owners[0], "FOR").andExpect(status().isCreated)
            close(m).andExpect(status().isCreated)
                .andExpect(jsonPath("$.status").value("PROVISIONAL")).andExpect(jsonPath("$.absenteeCloses").value(standing(today, null, today).absenteeCloses))
                .andExpect(jsonPath("$.protocolPostedOn").doesNotExist()).andExpect(jsonPath("$.finalOn").doesNotExist())
        }
        signedIn(m.owners[0]) { postProtocol(m, today).andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-SEC-001")) }
        signedIn(m.manager) {
            postProtocol(m, LocalDate.parse(today).plusDays(1).toString()).andExpect(status().isConflict)        // not a day to come
            postProtocol(m, today).andExpect(status().isCreated)
            postProtocol(m, today).andExpect(status().isConflict)                                                // entered once
        }
        val expected = standing(today, today, today)
        signedIn(m.owners[2]) {
            read("${m.base}/agenda/${m.item}/decision").andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("PROVISIONAL")).andExpect(jsonPath("$.protocolPostedOn").value(today))
                .andExpect(jsonPath("$.annulmentCloses").value(expected.annulmentCloses!!)).andExpect(jsonPath("$.finalOn").value(expected.finalOn!!))
        }
        jdbc.update("UPDATE assembly.protocol_posting SET posted_on = posted_on - 60 WHERE assembly_id = ?::uuid", m.base.substringAfterLast('/'))
        jdbc.update("DELETE FROM assembly.protocol_posting WHERE assembly_id = ?::uuid", m.base.substringAfterLast('/'))
        assertThat(jdbc.queryForObject("SELECT posted_on::text FROM assembly.protocol_posting WHERE assembly_id = ?::uuid", String::class.java, m.base.substringAfterLast('/'))).isEqualTo(today)
        assertThatThrownBy { jdbc.execute("TRUNCATE assembly.protocol_posting") }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `PM-SEC-002 how each owner voted is read by the manager, the board and the controller only - an owner and a stranger are a 403 naming the rule`() {
        val m = opened()
        val (ana, boris) = m.owners
        signedIn(m.manager) {
            vote(m, ana, "FOR").andExpect(status().isCreated)
            vote(m, boris, "FOR").andExpect(status().isCreated)
            vote(m, boris, "AGAINST", correction = "пълномощникът гласува против").andExpect(status().isCreated)
        }
        val stored = rows(m)
        for (reader in listOf(m.manager, mandated(m, "MB"), mandated(m, "CTL"))) {
            signedIn(reader) {
                read("${m.base}/agenda/${m.item}/votes").andExpect(status().isOk)
                    .andExpect(jsonPath("$.length()").value(3))
                    .andExpect(jsonPath("$[*].id").value(org.hamcrest.Matchers.contains(*stored.map { it["id"].toString() }.toTypedArray())))
                    .andExpect(jsonPath("$[2].ownerPartyId").value(boris.toString())).andExpect(jsonPath("$[2].choice").value("AGAINST"))
                    .andExpect(jsonPath("$[2].reverses").value(stored[1]["id"].toString())).andExpect(jsonPath("$[2].correction").value("пълномощникът гласува против"))
                    .andExpect(jsonPath("$[*].inEffect").value(org.hamcrest.Matchers.contains(true, false, true)))
            }
        }
        for (who in listOf(ana, boris, m.owners[2], person())) {
            signedIn(who) { read("${m.base}/agenda/${m.item}/votes").andExpect(status().isForbidden).andExpect(jsonPath("$.rule").value("PM-SEC-002")) }
        }
        val elsewhere = opened()                                                                             // another entrance's votes, asked through this one
        signedIn(elsewhere.manager) { vote(elsewhere, elsewhere.owners[0], "FOR").andExpect(status().isCreated) }
        signedIn(m.manager) {
            read("${m.base}/agenda/${UUID.randomUUID()}/votes").andExpect(status().isNotFound)
            read("${m.base}/agenda/${elsewhere.item}/votes").andExpect(status().isNotFound)
        }
    }

    @Test
    fun `PM-SEC-004 every read of how each owner voted is entered - served or refused, who and when - and no role deletes an entry`() {
        val m = opened()
        signedIn(m.manager) { vote(m, m.owners[0], "FOR").andExpect(status().isCreated) }
        val entries = { jdbc.queryForList("SELECT actor, login_subject, agenda_item_id, outcome, rule_id, at FROM assembly.vote_read WHERE entrance_id = ?::uuid ORDER BY at, id", m.entranceId) }
        assertThat(entries()).isEmpty()                                                                      // recording a vote reads nothing
        val stranger = person()
        signedIn(m.manager) { read("${m.base}/agenda/${m.item}/votes").andExpect(status().isOk) }
        signedIn(m.owners[1]) { read("${m.base}/agenda/${m.item}/votes").andExpect(status().isForbidden) }
        signedIn(stranger) { read("${m.base}/agenda/${m.item}/votes").andExpect(status().isForbidden) }
        signedIn(m.manager) { read("${m.base}/agenda/${UUID.randomUUID()}/votes").andExpect(status().isNotFound) }   // nothing there, nothing served, nothing entered
        val unknown = UUID.randomUUID()
        signedIn(stranger) { read("${m.base}/agenda/$unknown/votes").andExpect(status().isForbidden) }       // refused before anything is looked up: entered as asked
        read("${m.base}/agenda/${m.item}/votes").andExpect(status().isOk)                                   // sign-in switched off: given to nobody, and entered so
        val log = entries()
        assertThat(log.map { listOf(it["actor"], it["login_subject"], it["agenda_item_id"], it["outcome"], it["rule_id"]) }).containsExactly(
            listOf(m.manager, "login-${m.manager}", UUID.fromString(m.item), "SERVED", "PM-SEC-001"),
            listOf(m.owners[1], "login-${m.owners[1]}", UUID.fromString(m.item), "REFUSED", "PM-SEC-002"),
            listOf(stranger, "login-$stranger", UUID.fromString(m.item), "REFUSED", "PM-SEC-002"),
            listOf(stranger, "login-$stranger", unknown, "REFUSED", "PM-SEC-002"),
            listOf(null, null, UUID.fromString(m.item), "OPEN", null),
        )
        assertThat(log.map { it["at"] as java.sql.Timestamp }).allSatisfy { assertThat(it.toInstant()).isBetween(Instant.now().minusSeconds(60), Instant.now()) }
        assertThat(jdbc.update("UPDATE assembly.vote_read SET outcome = 'REFUSED', rule_id = 'PM-SEC-002' WHERE entrance_id = ?::uuid", m.entranceId)).isZero()
        assertThat(jdbc.update("DELETE FROM assembly.vote_read WHERE entrance_id = ?::uuid", m.entranceId)).isZero()
        assertThatThrownBy { jdbc.execute("TRUNCATE assembly.vote_read") }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThat(entries()).isEqualTo(log)
    }
}
