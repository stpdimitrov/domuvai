package zues.app.identity_org

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.reset
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import zues.app.policy.Asking
import zues.app.policy.Login
import zues.app.policy.Role
import zues.kernel.toSofiaDate
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

private const val MANDATE_REALM_IT = "https://id.example.test/realms/domuvai"

/**
 * Recording a mandate on a real Postgres, the application wired as it runs with sign-in configured and one
 * administrator named: the administrator ties a login and records a mandate over HTTP, and that manager then reads the
 * book — the whole chain from an empty deployment, with no SQL for anything the api serves. A successor ends the
 * predecessor's reading; the record of it all is never changed (PM-SEC-001, PM-GOV-004, PM-SEC-004, PM-SEC-011).
 * The requests carry the login a decoded token would give; the decoder itself is the web tests'. Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = ["domuvai.auth.issuer-uri=$MANDATE_REALM_IT", "domuvai.auth.audience=domuvai-api", "domuvai.auth.mode=", "domuvai.auth.admins=operator-m"])
@AutoConfigureMockMvc
class MandateAdministrationIT {

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
    @Autowired lateinit var administration: MandateAdministration
    @Autowired lateinit var roles: MandateRoles
    @Autowired lateinit var jdbc: JdbcTemplate
    @MockitoSpyBean lateinit var log: MandateActLog      // the real one; one test makes it fail

    private val run = UUID.randomUUID().toString().take(8)
    private val operator = Asking(Login(MANDATE_REALM_IT, "operator-m"), null)
    private val today: LocalDate get() = LocalDate.parse(toSofiaDate(Instant.now()))

    private fun entrance(): UUID {
        val condominium = UUID.randomUUID().also { jdbc.update("INSERT INTO registry.condominium (id, address) VALUES (?, ?)", it, "ул. Шипка $run") }
        return UUID.randomUUID().also { jdbc.update("INSERT INTO registry.entrance (id, condominium_id, label, management_form) VALUES (?, ?, 'А', 'GA')", it, condominium) }
    }

    private fun party(name: String): UUID = UUID.randomUUID().also { jdbc.update("INSERT INTO registry.party (id, full_name) VALUES (?, ?)", it, name) }

    private fun MockHttpServletRequestBuilder.by(subject: String) = with(jwt().jwt { it.subject(subject).issuer(MANDATE_REALM_IT) })
    private fun json(path: String, body: String) = post(path).contentType(MediaType.APPLICATION_JSON).content(body)
    private fun aMandate(body: String, party: UUID, from: LocalDate, to: LocalDate) =
        """{"body":"$body","partyId":"$party","validFrom":"$from","validTo":"$to","protocolRef":"Протокол № 3 от $from"}"""
    private fun acts(entrance: UUID) = jdbc.queryForList("SELECT act FROM identity_org.mandate_act WHERE entrance_id = ? ORDER BY at, act", String::class.java, entrance)
    private fun mandateId(entrance: UUID, party: UUID) = jdbc.queryForObject("SELECT id FROM identity_org.management_mandate WHERE entrance_id = ? AND party_id = ?", UUID::class.java, entrance, party)!!

    @Test
    fun `PM-SEC-001 from an empty deployment — the administrator ties a login and records a mandate, and that manager reads the book — nobody else can do either`() {
        val block = entrance()
        val maria = party("Мария Иванова")
        val ivan = party("Иван Петров")
        val mandates = "/api/identity/entrances/$block/mandates"
        val book = "/api/registry/entrances/$block/book"

        // Nobody is anybody yet: Maria is signed in and reads nothing.
        mvc.perform(get(book).param("purpose", "годишен отчет").by("login-$run-maria")).andExpect(status().isForbidden)
        // She cannot tie her own login, nor give herself the mandate; nor can the administrator read the book.
        mvc.perform(json("/api/identity/logins", """{"subject":"login-$run-maria","partyId":"$maria"}""").by("login-$run-maria")).andExpect(status().isForbidden)
        mvc.perform(json(mandates, aMandate("BM", maria, today.minusMonths(1), today.plusMonths(23))).by("login-$run-maria")).andExpect(status().isForbidden)
        mvc.perform(get(book).param("purpose", "проверка").by("operator-m")).andExpect(status().isForbidden)

        // The administrator says who she is and records the protocol's mandate …
        mvc.perform(json("/api/identity/logins", """{"subject":"login-$run-maria","partyId":"$maria"}""").by("operator-m")).andExpect(status().isOk)
        mvc.perform(json(mandates, aMandate("BM", maria, today.minusMonths(1), today.plusMonths(23))).by("operator-m"))
            .andExpect(status().isOk).andExpect(jsonPath("$.mandateId").isNotEmpty).andExpect(jsonPath("$.succeeded.length()").value(0))
        // … and now she reads the book. Ivan, signed in and tied, with no mandate, does not.
        mvc.perform(get(book).param("purpose", "годишен отчет").by("login-$run-maria")).andExpect(status().isOk)
        mvc.perform(json("/api/identity/logins", """{"subject":"login-$run-ivan","partyId":"$ivan"}""").by("operator-m")).andExpect(status().isOk)
        mvc.perform(get(book).param("purpose", "проверка").by("login-$run-ivan")).andExpect(status().isForbidden)
        // Even as the manager she records no mandate — not Ivan's, not an end to her own.
        mvc.perform(json(mandates, aMandate("CTL", ivan, today, today.plusYears(2))).by("login-$run-maria")).andExpect(status().isForbidden)
        mvc.perform(json("$mandates/${mandateId(block, maria)}/end", """{"on":"$today","protocolRef":"x"}""").by("login-$run-maria")).andExpect(status().isForbidden)

        assertThat(acts(block)).containsExactlyInAnyOrder("RECORD_REFUSED", "RECORDED", "RECORD_REFUSED", "END_REFUSED")
        assertThat(jdbc.queryForMap("SELECT by_subject, body, party_id, protocol_ref, rule_id FROM identity_org.mandate_act WHERE entrance_id = ? AND act = 'RECORDED'", block).values)
            .containsExactly("operator-m", "BM", maria, "Протокол № 3 от ${today.minusMonths(1)}", "PM-SEC-001")
        assertThat(jdbc.queryForObject("SELECT protocol_ref FROM identity_org.management_mandate WHERE entrance_id = ?", String::class.java, block)).startsWith("Протокол № 3")
    }

    @Test
    fun `PM-GOV-004 a successor recorded ends the predecessor on its first day — he reads until then and not after, and each past day keeps its answer`() {
        val block = entrance()
        val petar = party("Петър Димов")
        val maria = party("Мария Иванова")
        val mandates = "/api/identity/entrances/$block/mandates"
        val book = "/api/registry/entrances/$block/book"
        for ((subject, who) in listOf("login-$run-petar" to petar, "login-$run-maria2" to maria))
            mvc.perform(json("/api/identity/logins", """{"subject":"$subject","partyId":"$who"}""").by("operator-m")).andExpect(status().isOk)

        // Petar's mandate ran out two months ago and nobody was elected: he continues, and reads.
        val began = today.minusMonths(26)
        mvc.perform(json(mandates, aMandate("BM", petar, began, began.plusYears(2))).by("operator-m")).andExpect(status().isOk)
        val first = mandateId(block, petar)
        mvc.perform(get(book).param("purpose", "проверка").by("login-$run-petar")).andExpect(status().isOk)

        // Maria is elected, from last week: recording her mandate ends his on her first day.
        val took = today.minusDays(7)
        mvc.perform(json(mandates, aMandate("BM", maria, took, took.plusYears(2))).by("operator-m"))
            .andExpect(status().isOk).andExpect(jsonPath("$.succeeded[0]").value(first.toString()))
        val second = mandateId(block, maria)
        assertThat(jdbc.queryForObject("SELECT succeeded_at FROM identity_org.management_mandate WHERE id = ?", java.sql.Date::class.java, first)!!.toLocalDate()).isEqualTo(took)
        mvc.perform(get(book).param("purpose", "проверка").by("login-$run-petar")).andExpect(status().isForbidden)
        mvc.perform(get(book).param("purpose", "годишен отчет").by("login-$run-maria2")).andExpect(status().isOk)

        // PM-SEC-011: who held the office on each day, with the mandate that says so.
        assertThat(roles.holdersOn(block, began.minusDays(1))).isEmpty()
        assertThat(roles.holdersOn(block, took.minusDays(1))).containsExactly(RoleHolder(petar, Role.BM, first))
        assertThat(roles.holdersOn(block, took)).containsExactly(RoleHolder(maria, Role.BM, second))
        assertThat(roles.holdersOn(block, today)).containsExactly(RoleHolder(maria, Role.BM, second))

        // The end is recorded once: it is not moved, by the api or by a second successor.
        mvc.perform(json("$mandates/$first/end", """{"on":"${today.minusYears(1)}","protocolRef":"Протокол"}""").by("operator-m")).andExpect(status().isConflict)
        mvc.perform(json("$mandates/$second/end", """{"on":"${took.minusDays(1)}","protocolRef":"Протокол"}""").by("operator-m")).andExpect(status().isBadRequest)      // before it began
        mvc.perform(json("$mandates/$second/end", """{"on":"${today.plusDays(1)}","protocolRef":"Протокол"}""").by("operator-m")).andExpect(status().isBadRequest)     // not yet happened
        mvc.perform(json("$mandates/$second/end", """{"on":"$today"}""").by("operator-m")).andExpect(status().isBadRequest)                                            // on no basis
        // A manager is recorded after the one it succeeds: one beside Maria, or behind her, is refused — nothing would end it.
        for (first in listOf(took, took.minusMonths(3)))
            mvc.perform(json(mandates, aMandate("BM", petar, first, first.plusYears(1))).by("operator-m")).andExpect(status().isConflict)
        mvc.perform(json(mandates, aMandate("MB", petar, took, took.plusYears(1))).by("operator-m")).andExpect(status().isConflict)
        mvc.perform(json("$mandates/${UUID.randomUUID()}/end", """{"on":"$today","protocolRef":"Протокол"}""").by("operator-m")).andExpect(status().isNotFound)
        mvc.perform(json("/api/identity/entrances/${entrance()}/mandates/$second/end", """{"on":"$today","protocolRef":"Протокол"}""").by("operator-m")).andExpect(status().isNotFound)   // not that entrance's
        assertThat(roles.holdersOn(block, took.minusDays(1))).containsExactly(RoleHolder(petar, Role.BM, first))
        assertThat(jdbc.queryForMap("SELECT mandate_id, on_day, succeeded_by, by_subject, rule_id FROM identity_org.mandate_act WHERE entrance_id = ? AND act = 'ENDED'", block).values)
            .containsExactly(first, java.sql.Date.valueOf(took), second, "operator-m", "PM-GOV-004")

        // What the table will not take, said to the administrator: a mandate longer than a mandate may be, a party or an entrance nobody registered.
        mvc.perform(json(mandates, aMandate("CTL", petar, today, today.plusYears(2).plusDays(1))).by("operator-m")).andExpect(status().isBadRequest).andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("PM-GOV-004")))
        mvc.perform(json(mandates, aMandate("CTL", UUID.randomUUID(), today, today.plusYears(1))).by("operator-m")).andExpect(status().isBadRequest)
        mvc.perform(json("/api/identity/entrances/${UUID.randomUUID()}/mandates", aMandate("CTL", petar, today, today.plusYears(1))).by("operator-m")).andExpect(status().isNotFound)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_org.management_mandate WHERE entrance_id = ?", Long::class.java, block)).isEqualTo(2)
    }

    @Test
    fun `PM-GOV-004 a board member recorded ends an earlier manager and members whose dates ran out — a fellow member still in term keeps the office`() {
        val block = entrance()
        val (manager, expired, first, second, newcomer) = listOf("Управител", "Изтекъл член", "Първи член", "Втори член", "Нов член").map { party(it) }
        val took = today.minusDays(7)
        val elected = took.minusYears(1)                                                            // the day the sitting board was elected
        fun record(body: String, who: UUID, from: LocalDate, to: LocalDate) = (administration.record(operator, block, RecordMandate(body, who, from, to, "Протокол")) as MandateAnswer.Done).value
        val earlierManager = record("BM", party("Предишен управител"), elected.minusYears(3), elected.minusYears(2)).mandateId   // ran out, still acting
        val expiredMember = record("MB", expired, elected.minusYears(2), elected)                    // runs up to the election day, and not into it
        assertThat(expiredMember.succeeded).containsExactly(earlierManager)                         // a board replaces a manager
        // the board is elected: its first member ends the one whose dates ran out; the second, elected the same day, ends nobody
        assertThat(record("MB", first, elected, elected.plusYears(2)).succeeded).containsExactly(expiredMember.mandateId)
        assertThat(record("MB", second, elected, elected.plusYears(2)).succeeded).isEmpty()
        val controller = record("CTL", party("Контрольор"), elected, elected.plusYears(2)).mandateId
        // a member added later cuts nobody short: the others are within their own dates
        val added = record("MB", newcomer, took, took.plusYears(2))
        assertThat(added.succeeded).isEmpty()
        assertThat(roles.holdersOn(block, today).filter { it.role == Role.MB }.map { it.partyId }).containsExactlyInAnyOrder(first, second, newcomer)
        assertThat(roles.holdersOn(block, elected.minusDays(1)).map { it.partyId }).containsExactly(expired)   // and the day before the election keeps its answer
        // … and a sole manager replaces the board, sitting members too — a controller is nobody's predecessor
        val replaced = record("BM", manager, today, today.plusYears(2))
        assertThat(replaced.succeeded).containsExactlyInAnyOrder(mandateId(block, first), mandateId(block, second), added.mandateId)
        assertThat(roles.holdersOn(block, today).map { it.partyId to it.role }).contains(manager to Role.BM).doesNotContain(first to Role.MB, second to Role.MB, newcomer to Role.MB)
        assertThat(roles.holdersOn(block, today).map { it.mandateId }).contains(controller)
    }

    @Test
    fun `PM-SEC-004 the act and its entry stand or fall together, and the record is never changed or removed`() {
        val block = entrance()
        val maria = party("Мария Иванова")
        val request = RecordMandate("BM", maria, today, today.plusYears(2), "Протокол № 4")
        doThrow(IllegalStateException("the entry could not be written")).whenever(log).record(argThat { act == "RECORDED" && entranceId == block })
        assertThatThrownBy { administration.record(operator, block, request) }.isInstanceOf(IllegalStateException::class.java)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_org.management_mandate WHERE entrance_id = ?", Long::class.java, block)).isEqualTo(0)
        reset(log)
        val recorded = (administration.record(operator, block, request) as MandateAnswer.Done).value.mandateId
        doThrow(IllegalStateException("the entry could not be written")).whenever(log).record(argThat { act == "ENDED" && entranceId == block })
        assertThatThrownBy { administration.end(operator, block, recorded, today, "Протокол") }.isInstanceOf(IllegalStateException::class.java)
        assertThat(jdbc.queryForObject("SELECT succeeded_at FROM identity_org.management_mandate WHERE id = ?", java.sql.Date::class.java, recorded)).isNull()
        // a successor whose predecessor's end cannot be entered is not recorded either
        assertThatThrownBy { administration.record(operator, block, RecordMandate("BM", party("Наследник"), today.plusDays(1), today.plusYears(1), "Протокол № 5")) }.isInstanceOf(IllegalStateException::class.java)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identity_org.management_mandate WHERE entrance_id = ?", Long::class.java, block)).isEqualTo(1)
        reset(log)

        jdbc.update("UPDATE identity_org.mandate_act SET act = 'ENDED', by_subject = 'somebody-else' WHERE entrance_id = ?", block)   // ignored
        jdbc.update("DELETE FROM identity_org.mandate_act WHERE entrance_id = ?", block)                                               // ignored
        assertThatThrownBy { jdbc.execute("TRUNCATE identity_org.mandate_act") }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThat(jdbc.queryForMap("SELECT act, by_subject, mandate_id FROM identity_org.mandate_act WHERE entrance_id = ?", block).values).containsExactly("RECORDED", "operator-m", recorded)

        val row = "INSERT INTO identity_org.mandate_act (id, act, entrance_id, mandate_id, body, party_id, valid_from, valid_to, on_day, protocol_ref, by_issuer, by_subject, rule_id, at) " +
            "VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?::date, ?::date, ?::date, ?, 'i', 'op', ?, now())"
        for (bad in listOf(
            arrayOf("RECORDED", block, null, "BM", maria, "2026-01-01", "2027-01-01", null, "п", "PM-SEC-001"),       // names no mandate
            arrayOf("RECORDED", block, recorded, "BM", maria, "2026-01-01", "2027-01-01", null, null, "PM-SEC-001"),  // no protocol
            arrayOf("ENDED", block, recorded, null, null, null, null, null, null, "PM-SEC-001"),                      // no day
            arrayOf("PEEKED", block, recorded, null, null, null, null, "2026-01-01", null, "PM-SEC-001"),
            arrayOf("ENDED", block, recorded, null, null, null, null, "2026-01-01", null, "a rule"),
        )) assertThatThrownBy { jdbc.update(row, *bad) }.describedAs(bad.joinToString()).isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { jdbc.update(row, "ENDED", block, recorded, null, null, null, null, "2026-01-01", null, "PM-SEC-001") }.isInstanceOf(DataIntegrityViolationException::class.java)   // an end on no basis

        // PM-SEC-011 — the mandate's own table: a mandate is added and its end recorded once; nothing else about it changes, and it is not removed.
        for (change in listOf(
            "SET succeeded_at = valid_from - 1", "SET protocol_ref = 'друг протокол'", "SET party_id = '${party("Друг")}'", "SET valid_from = valid_from - 30",
            "SET valid_to = valid_to + 1", "SET body = 'CTL'", "SET succeeded_at = current_date, valid_to = valid_to + 1",
        )) assertThatThrownBy { jdbc.update("UPDATE identity_org.management_mandate $change WHERE id = ?", recorded) }.describedAs(change).isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { jdbc.update("DELETE FROM identity_org.management_mandate WHERE id = ?", recorded) }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { jdbc.execute("TRUNCATE identity_org.management_mandate") }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThat((administration.end(operator, block, recorded, today, "Протокол № 6") as MandateAnswer.Done).value.on).isEqualTo(today)
        assertThatThrownBy { jdbc.update("UPDATE identity_org.management_mandate SET succeeded_at = current_date - 30 WHERE id = ?", recorded) }.isInstanceOf(DataIntegrityViolationException::class.java)   // not moved
        assertThatThrownBy { jdbc.update("UPDATE identity_org.management_mandate SET succeeded_at = NULL WHERE id = ?", recorded) }.isInstanceOf(DataIntegrityViolationException::class.java)               // nor taken back
    }
}
