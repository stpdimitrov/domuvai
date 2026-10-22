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
 * Quorum against real PostgreSQL and the real registry, through the HTTP layer with real sign-ins: the meter, a
 * sitting that opens on its quorum, one that falls short, and a weighing kept as evidence. Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class SittingPersistenceIT {

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
            "INSERT INTO identity_org.management_mandate (id, entrance_id, body, party_id, valid_from, valid_to) VALUES (gen_random_uuid(), ?, ?, ?, current_date + ?, current_date + ?)",
            entranceId, body, it, from, to,
        )
        signedInAs = "login-$it"
    }

    /** An owner of a unit of the entrance since the start of last year — the whole entrance, the first time it is asked for. */
    private fun owner(entranceId: UUID): UUID = person().also {
        val existing = jdbc.queryForList("SELECT id FROM registry.unit WHERE entrance_id = ?", UUID::class.java, entranceId)
        val unit = existing.firstOrNull() ?: UUID.fromString(
            created("/api/registry/entrances/$entranceId/units", """{"units":[{"designation":"ап. 1","unitType":"FLAT","idealParts":"100.0000","separateEntrance":false}]}""")
                .get("unitIds")[0].asText(),
        )
        created("/api/registry/entrances/$entranceId/units/$unit/titles", """{"partyId":"$it","titleRole":"OWN","share":"${if (existing.isEmpty()) "0.5" else "0.25"}","validFrom":"2025-01-01"}""")
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

    /** A noticed assembly meeting today: convened and noticed a month ahead as the law wants, then the day is made today. */
    private fun meetingToday(entranceId: UUID, coSignatory: UUID): String {
        val proxies = assembly(entranceId)
        val base = proxies.removeSuffix("/proxies")
        created("$base/notice-posting", """{"postedAt":"${Instant.now()}","coSignatoryPartyId":"$coSignatory","photoHash":"${"3f".repeat(32)}"}""")
        jdbc.update("UPDATE assembly.assembly SET scheduled_at = now(), mode = 'HYBRID' WHERE id = ?::uuid", base.substringAfterLast("/"))
        return base
    }

    private fun present(base: String, attendee: UUID, channel: String = "IN_PERSON") =
        mvc.perform(post("$base/attendance").contentType(MediaType.APPLICATION_JSON).content("""{"attendeePartyId":"$attendee","channel":"$channel"}"""))

    private fun register(base: String): JsonNode = json.readTree(mvc.perform(get("$base/attendance")).andExpect(status().isOk).andReturn().response.contentAsString)

    private fun meter(base: String): JsonNode = json.readTree(mvc.perform(get("$base/quorum")).andExpect(status().isOk).andReturn().response.contentAsString)

    private fun open(base: String) = mvc.perform(post("$base/sittings").contentType(MediaType.APPLICATION_JSON))

    private val threshold = constantOn("GA_QUORUM_PCT", toSofiaDate(Instant.now()))

    @Test
    fun `PM-GA-012 a sitting opens on its quorum - weighed by title share from the register, by whoever runs the assembly, and kept as evidence`() {
        val entranceId = entrance()
        val (ana, boris, vera) = listOf(owner(entranceId), owner(entranceId), owner(entranceId))      // a half, a quarter, a quarter of the one unit
        val base = meetingToday(entranceId, ana)
        val manager = jdbc.queryForObject("SELECT convened_by FROM assembly.assembly WHERE entrance_id = ?", UUID::class.java, entranceId)!!
        signedIn(boris) { created("$base/proxies", """{"agentPartyId":"$ana","agentKind":"OWNER","scope":"WHOLE_AGENDA","form":"WRITTEN"}""") }
        assertThat(meter(base).get("representedPct").asText()).isEqualTo("0")
        assertThat(meter(base).get("met").asBoolean()).isFalse()
        present(base, ana).andExpect(status().isCreated)                             // brings Boris: 50 + 25
        val live = meter(base)
        assertThat(live.get("representedPct").asText()).isEqualTo("75")
        assertThat(live.get("thresholdPct").asText()).isEqualTo(threshold.value)
        assertThat(live.get("thresholdSource").asText()).isEqualTo(threshold.source)
        assertThat(live.get("met").asBoolean()).isTrue()
        assertThat(live.get("sittings")).isEmpty()

        for (who in listOf(ana, vera, null)) signedIn(who) { open(base).andExpect(status().isForbidden) }     // an owner, another owner, nobody
        assertThat(jdbc.queryForObject("SELECT count(*) FROM assembly.session WHERE entrance_id = ?", Int::class.java, entranceId)).isZero()

        val sitting = json.readTree(open(base).andExpect(status().isCreated).andReturn().response.contentAsString)
        assertThat(sitting.get("quorumMet").asBoolean()).isTrue()
        assertThat(sitting.get("sessionNo").asInt()).isEqualTo(1)
        assertThat(sitting.get("representedPct").asText()).isEqualTo("75")
        assertThat(sitting.get("weighedBy").asText()).isEqualTo("$manager")
        assertThat(sitting.hasNonNull("openedAt")).isTrue()
        assertThat(jdbc.queryForObject("SELECT status FROM assembly.assembly WHERE entrance_id = ?", String::class.java, entranceId)).isEqualTo("OPEN")
        // PM-GA-001: this year's assembly is held, and it is this one
        val year = json.readTree(mvc.perform(get("/api/assembly/entrances/$entranceId/years/${toSofiaDate(Instant.now()).take(4)}")).andExpect(status().isOk).andReturn().response.contentAsString)
        assertThat(year.get("held").asInt()).isEqualTo(1)
        assertThat(year.get("met").asBoolean()).isTrue()
        assertThat(year.get("firstAssemblyId").asText()).isEqualTo(base.substringAfterLast("/"))
        open(base).andExpect(status().isConflict)                                    // once
        assertThat(meter(base).get("sittings")).hasSize(1)
        present(base, vera).andExpect(status().isCreated)                            // the register goes on being kept in an open sitting

        val row = jdbc.queryForMap("SELECT * FROM assembly.session WHERE entrance_id = ?", entranceId)
        assertThat(row["basis"] as String).contains("$ana").contains("$boris").doesNotContain("$vera")           // what was weighed, as it was weighed
        assertThat(row["basis_hash"]).isEqualTo(sitting.get("basisHash").asText())
        assertThat(listOf(row["law_version"], row["engine_version"])).containsExactly(zues.law.CATALOGUE_VERSION, zues.law.ENGINE_VERSION)
        assertThat(row["threshold_constant"]).isEqualTo("GA_QUORUM_PCT@${threshold.inForceFrom}")
        assertThat(row["login_subject"]).isEqualTo("login-$manager")

        // evidence: not rewritten, removed or emptied — and a row cannot say it opened on a quorum it did not have
        jdbc.update("UPDATE assembly.session SET represented = 100 WHERE entrance_id = ?", entranceId)
        jdbc.update("DELETE FROM assembly.session WHERE entrance_id = ?", entranceId)
        assertThat(jdbc.queryForObject("SELECT represented FROM assembly.session WHERE entrance_id = ?", java.math.BigDecimal::class.java, entranceId)).isEqualByComparingTo("75")
        assertThatThrownBy { jdbc.execute("TRUNCATE assembly.session CASCADE") }.hasMessageContaining("is not emptied")
        val copy = "INSERT INTO assembly.session (id, entrance_id, assembly_id, session_no, opened_at, represented, quorum_met, weighed_at, weighed_on, threshold_pct, threshold_constant, " +
            "threshold_verified, basis, basis_hash, law_version, engine_version, weighed_by, login_issuer, login_subject) " +
            "SELECT gen_random_uuid(), entrance_id, assembly_id, 2, %s, %s, %s, weighed_at, weighed_on, threshold_pct, threshold_constant, threshold_verified, basis, basis_hash, " +
            "law_version, engine_version, weighed_by, login_issuer, login_subject FROM assembly.session WHERE entrance_id = ?"
        assertThatThrownBy { jdbc.update(copy.format("opened_at", "50.99", "true"), entranceId) }.isInstanceOf(DataIntegrityViolationException::class.java)     // met, on less than the threshold
        assertThatThrownBy { jdbc.update(copy.format("opened_at", "50.99", "false"), entranceId) }.isInstanceOf(DataIntegrityViolationException::class.java)    // opened without it
        assertThatThrownBy { jdbc.update(copy.format("NULL", "75", "true"), entranceId) }.isInstanceOf(DataIntegrityViolationException::class.java)            // met, and never opened
    }

    @Test
    fun `PM-GA-012 a weighing that falls short is kept and opens nothing - half of the entrance is not a quorum`() {
        val entranceId = entrance()
        val ana = owner(entranceId)                                                  // a half
        owner(entranceId)
        val base = meetingToday(entranceId, ana)
        present(base, ana).andExpect(status().isCreated)
        assertThat(meter(base).get("representedPct").asText()).isEqualTo("50")
        assertThat(meter(base).get("met").asBoolean()).isFalse()
        val sitting = json.readTree(open(base).andExpect(status().isCreated).andReturn().response.contentAsString)
        assertThat(sitting.get("quorumMet").asBoolean()).isFalse()
        assertThat(sitting.hasNonNull("openedAt")).isFalse()
        assertThat(jdbc.queryForObject("SELECT status FROM assembly.assembly WHERE entrance_id = ?", String::class.java, entranceId)).isEqualTo("NOTICED")
        // PM-GA-001: a sitting weighed short holds no assembly
        val year = json.readTree(mvc.perform(get("/api/assembly/entrances/$entranceId/years/${toSofiaDate(Instant.now()).take(4)}")).andExpect(status().isOk).andReturn().response.contentAsString)
        assertThat(year.get("held").asInt()).isZero()
        assertThat(year.get("met").asBoolean()).isFalse()
        open(base).andExpect(status().isConflict)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM assembly.session WHERE entrance_id = ?", Int::class.java, entranceId)).isEqualTo(1)
    }

    @Test
    fun `PM-GA-012 a unit with both an owner and a holder of a right of use - the meter says why and no sitting is weighed, nor before the hour`() {
        val entranceId = entrance()
        val ana = owner(entranceId)
        val base = meetingToday(entranceId, ana)
        present(base, ana).andExpect(status().isCreated)
        jdbc.update("UPDATE assembly.assembly SET scheduled_at = now() + interval '30 seconds' WHERE entrance_id = ? AND (now() + interval '30 seconds')::date = now()::date", entranceId)
        val early = jdbc.queryForObject("SELECT scheduled_at > now() FROM assembly.assembly WHERE entrance_id = ?", Boolean::class.java, entranceId)!!
        if (early) open(base).andExpect(status().isConflict)                         // not before the hour the notice stated
        jdbc.update("UPDATE assembly.assembly SET scheduled_at = now() - interval '1 second' WHERE entrance_id = ?", entranceId)
        val unit = jdbc.queryForObject("SELECT id FROM registry.unit WHERE entrance_id = ?", UUID::class.java, entranceId)
        created("/api/registry/entrances/$entranceId/units/$unit/titles", """{"partyId":"${party()}","titleRole":"USR","share":"1","validFrom":"2025-01-01"}""")
        val stuck = meter(base)
        assertThat(stuck.get("met").asBoolean()).isFalse()
        assertThat(stuck.get("cannotWeigh")[0].asText()).contains("$unit").contains("TODO(legal): PM-GA-012")
        val refused = open(base).andExpect(status().isConflict).andReturn().response.contentAsString
        assertThat(refused).contains("cannot be weighed")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM assembly.session WHERE entrance_id = ?", Int::class.java, entranceId)).isZero()
    }

    @Test
    fun `PM-GA-013 an hour after a first sitting weighed short, the postponed one opens at the reduced share - and the schema caps no count of sittings`() {
        val entranceId = entrance()
        val ana = owner(entranceId)                                                  // a half
        owner(entranceId)
        val base = meetingToday(entranceId, ana)
        val manager = jdbc.queryForObject("SELECT convened_by FROM assembly.assembly WHERE entrance_id = ?", UUID::class.java, entranceId)!!
        // the first sitting, an hour and more ago, with nobody present: weighed short (as recorded, the row is insert-only)
        val hour = constantOn("GA_POSTPONE_MINUTES", toSofiaDate(Instant.now())).value.toLong()
        val earlier = Instant.now().minus(hour + 5, ChronoUnit.MINUTES)
        org.junit.jupiter.api.Assumptions.assumeTrue(toSofiaDate(earlier) == toSofiaDate(Instant.now()), "not across midnight in Sofia")
        jdbc.update("UPDATE assembly.assembly SET scheduled_at = ? WHERE entrance_id = ?", java.sql.Timestamp.from(earlier.minusSeconds(60)), entranceId)
        jdbc.update(
            "INSERT INTO assembly.session (id, entrance_id, assembly_id, session_no, opened_at, represented, quorum_met, weighed_at, weighed_on, threshold_pct, threshold_constant, " +
                "threshold_verified, basis, basis_hash, law_version, engine_version, weighed_by, login_issuer, login_subject) " +
                "SELECT gen_random_uuid(), entrance_id, id, 1, NULL, 0, false, ?, ?::date, 51, 'GA_QUORUM_PCT@2009-01-01', true, 'sitting 1', encode(sha256('sitting 1'), 'hex'), 'v', 'e', ?, 'realm', 'login' " +
                "FROM assembly.assembly WHERE entrance_id = ?",
            java.sql.Timestamp.from(earlier), toSofiaDate(earlier), manager, entranceId,
        )
        val meter = meter(base)
        assertThat(meter.get("sessionNo").asInt()).isEqualTo(2)
        assertThat(meter.get("thresholdPct").asText()).isEqualTo(constantOn("GA_REDUCED_QUORUM_PCT", toSofiaDate(Instant.now())).value)
        present(base, ana).andExpect(status().isCreated)                             // entered for the postponed sitting
        assertThat(jdbc.queryForObject("SELECT session_no FROM assembly.attendance WHERE entrance_id = ?", Int::class.java, entranceId)).isEqualTo(2)
        val sitting = json.readTree(open(base).andExpect(status().isCreated).andReturn().response.contentAsString)
        assertThat(sitting.get("sessionNo").asInt()).isEqualTo(2)
        assertThat(sitting.get("quorumMet").asBoolean()).isTrue()                    // a half is more than the reduced share
        assertThat(jdbc.queryForObject("SELECT status FROM assembly.assembly WHERE entrance_id = ?", String::class.java, entranceId)).isEqualTo("OPEN")
        // how many sittings there may be is the law's: the schema numbers them from one and caps nothing (#189)
        assertThat(jdbc.queryForList("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conrelid = 'assembly.session'::regclass AND contype = 'c'", String::class.java))
            .noneMatch { it.contains("<= 3") || it.contains("BETWEEN") }.anyMatch { it.contains("session_no >= 1") }
    }

    @Test
    fun `PM-GA-015 an owner of the whole entrance at the notice date raises the quorum to the higher share, kept with the weighing`() {
        val entranceId = entrance()
        val sole = person()
        val unit = created("/api/registry/entrances/$entranceId/units", """{"units":[{"designation":"ап. 1","unitType":"FLAT","idealParts":"100.0000","separateEntrance":false}]}""")
            .get("unitIds")[0].asText()
        created("/api/registry/entrances/$entranceId/units/$unit/titles", """{"partyId":"$sole","titleRole":"OWN","share":"1","validFrom":"2025-01-01"}""")
        val base = meetingToday(entranceId, sole)
        val higher = constantOn("GA_CONCENTRATED_QUORUM_PCT", toSofiaDate(Instant.now()))
        val meter = meter(base)
        assertThat(meter.get("thresholdPct").asText()).isEqualTo(higher.value)
        assertThat(meter.get("concentratedBy").asText()).isEqualTo("$sole")
        assertThat(meter.get("concentratedPct").asText()).isEqualTo("100")
        present(base, sole).andExpect(status().isCreated)
        open(base).andExpect(status().isCreated)
        val row = jdbc.queryForMap("SELECT threshold_constant, threshold_pct, quorum_met, basis FROM assembly.session WHERE entrance_id = ?", entranceId)
        assertThat(row["threshold_constant"]).isEqualTo("GA_CONCENTRATED_QUORUM_PCT@${higher.inForceFrom}")
        assertThat(row["threshold_pct"] as java.math.BigDecimal).isEqualByComparingTo(higher.value)
        assertThat(row["quorum_met"]).isEqualTo(true)
        assertThat(row["basis"] as String).contains("one owner, $sole, held 100 at the notice")
    }
}
