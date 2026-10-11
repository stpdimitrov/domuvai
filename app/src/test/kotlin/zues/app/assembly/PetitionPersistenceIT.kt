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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import zues.app.identity_org.Logins
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The owners' petition against real PostgreSQL and the real registry: titles written through the registry are
 * what the petition weighs, the one who signs is the party the sign-in is tied to, and the database holds what the
 * service decided. Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class PetitionPersistenceIT {

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

    /** Four units of 12.5, 7.5, 30 and 50 percent, each with its owner from the start of the year. */
    private fun owned(entranceId: UUID): List<Pair<UUID, UUID>> {
        val units = created(
            "/api/registry/entrances/$entranceId/units",
            """{"units":[{"designation":"ап. 1","unitType":"FLAT","idealParts":"12.5000","separateEntrance":false},
                         {"designation":"ап. 2","unitType":"FLAT","idealParts":"7.5000","separateEntrance":false},
                         {"designation":"ап. 3","unitType":"FLAT","idealParts":"30.0000","separateEntrance":false},
                         {"designation":"ап. 4","unitType":"FLAT","idealParts":"50.0000","separateEntrance":false}]}""",
        ).get("unitIds").map { UUID.fromString(it.asText()) }
        return units.map { unit -> party().also { title(entranceId, unit, it, "OWN") } to unit }
    }

    private fun party() = UUID.fromString(created("/api/registry/parties", """{"fullName":"Иван Петров"}""").get("partyId").asText())

    @Autowired lateinit var logins: Logins
    private val realm = "https://id.example.test/realms/${UUID.randomUUID()}"

    /** The login of a party: tied to it in the api's own record, under this test's issuer. */
    private fun loginOf(party: UUID): String = "login-$party".also { logins.tie(realm, it, party) }

    /** The request as this login's: a token the api would have accepted, naming the subject under the test's issuer. */
    private fun MockHttpServletRequestBuilder.by(subject: String) = with(jwt().jwt { it.subject(subject).issuer(realm) })

    private fun asked(subject: String, path: String, body: String = "{}") = mvc.perform(post(path).by(subject).contentType(MediaType.APPLICATION_JSON).content(body))

    private fun createdBy(subject: String, path: String, body: String = "{}"): JsonNode =
        json.readTree(asked(subject, path, body).andExpect(status().isCreated).andReturn().response.contentAsString)

    private fun title(entranceId: UUID, unitId: UUID, partyId: UUID, role: String) = created(
        "/api/registry/entrances/$entranceId/units/$unitId/titles", """{"partyId":"$partyId","titleRole":"$role","share":"1","validFrom":"2026-01-01"}""",
    )

    private val meeting get() =
        """{"scheduledAt":"${Instant.now().plus(30, ChronoUnit.DAYS)}","place":"фоайето на вх. А","mode":"IN_PERSON","demandUnmet":"Искането е връчено на управителя; събрание не е свикано."}"""

    @Test
    fun `PM-GA-003 owners' signatures accumulate to the threshold, and one of them convenes the assembly the database records as theirs`() {
        val entranceId = entrance()
        val (first, second, third, _) = owned(entranceId).map { it.first }
        val base = "/api/assembly/entrances/$entranceId/petitions"

        val (ana, boris, vera) = listOf(first, second, third).map { loginOf(it) }

        val opened = createdBy(ana, base, """{"subject":"Ремонт на покрива"}""")
        val id = opened.get("id").asText()
        assertThat(opened.get("openedBy").asText()).isEqualTo(first.toString())
        assertThat(opened.get("heldPct").asText()).isEqualTo("12.5")
        assertThat(opened.get("unlocked").asBoolean()).isFalse()
        asked(ana, "$base/$id/assembly", meeting).andExpect(status().isConflict)

        // Ana cannot sign for Boris: a body naming him changes nothing, and her own second signature is refused
        asked(ana, "$base/$id/signatures", """{"partyId":"$second"}""").andExpect(status().isConflict)
        assertThat(jdbc.queryForList("SELECT party_id FROM assembly.petition_signature WHERE petition_id = ?::uuid", UUID::class.java, id)).containsExactly(first)

        val signed = createdBy(boris, "$base/$id/signatures")
        assertThat(signed.get("heldPct").asText()).isEqualTo("20")                    // 12.5 + 7.5: exactly the threshold
        assertThat(signed.get("thresholdPct").asText()).isEqualTo("20")
        assertThat(signed.get("thresholdVerified").asBoolean()).isFalse()
        assertThat(signed.get("unlocked").asBoolean()).isTrue()
        asked(boris, "$base/$id/signatures").andExpect(status().isConflict)
        asked(vera, "$base/$id/assembly", meeting).andExpect(status().isBadRequest)   // an owner, not a signatory

        val convened = createdBy(boris, "$base/$id/assembly", """{"convenedBy":"$third",${meeting.drop(1)}""")   // the body names Vera; Boris convenes
        assertThat(convened.get("convenedAs").asText()).isEqualTo("OWNERS")
        val assemblyId = convened.get("assemblyId").asText()
        val row = jdbc.queryForMap("SELECT * FROM assembly.assembly WHERE id = ?::uuid", assemblyId)
        assertThat(row["petition_id"].toString()).isEqualTo(id)
        assertThat(row["convened_by"]).isEqualTo(second)
        val unlock = jdbc.queryForMap("SELECT * FROM assembly.petition_unlock WHERE petition_id = ?::uuid", id)
        assertThat(unlock["held_pct"] as java.math.BigDecimal).isEqualByComparingTo("20")
        assertThat(unlock["threshold_pct"] as java.math.BigDecimal).isEqualByComparingTo("20")
        assertThat(unlock["threshold_constant"] as String).startsWith("GA_PETITION_MIN_PCT@")
        assertThat(unlock["threshold_verified"]).isEqualTo(false)
        assertThat(listOf(unlock["law_version"], unlock["engine_version"])).doesNotContainNull()
        assertThat(unlock["demand_unmet_note"] as String).startsWith("Искането е връчено")
        assertThat(listOf(unlock["login_issuer"], unlock["login_subject"])).containsExactly(realm, boris)
        assertThat(json.readTree(mvc.perform(get("$base/$id")).andReturn().response.contentAsString).get("assemblyId").asText()).isEqualTo(assemblyId)

        // once: no second assembly on the petition, by the service or by hand; and no signature after it
        asked(ana, "$base/$id/assembly", meeting).andExpect(status().isConflict)
        asked(vera, "$base/$id/signatures").andExpect(status().isConflict)
        val copy = "INSERT INTO assembly.assembly(id, entrance_id, convened_by, convened_as, place, scheduled_at, mode, status, notice_content_changed_at, petition_id) " +
            "SELECT gen_random_uuid(), %s, convened_by, %s, place, scheduled_at, mode, status, notice_content_changed_at, %s FROM assembly.assembly WHERE id = '$assemblyId'"
        fun refused(entrance: String, convenedAs: String, petition: String, constraint: String) =
            assertThatThrownBy { jdbc.update(copy.format(entrance, convenedAs, petition)) }
                .isInstanceOf(DataIntegrityViolationException::class.java).hasMessageContaining(constraint)
        refused("entrance_id", "convened_as", "petition_id", "assembly_one_per_petition")
        refused("entrance_id", "'OWNERS'", "NULL", "assembly_owners_convene_on_petition")               // the owners, on no petition
        refused("entrance_id", "'BM'", "petition_id", "assembly_owners_convene_on_petition")            // an office, on a petition
        val another = createdBy(vera, base, """{"subject":"Друго"}""").get("id").asText()                // 30%: open, never convened on
        refused("entrance_id", "'OWNERS'", "'$another'", "assembly_on_an_unlocked_petition")            // a petition nothing unlocked
        assertThat(jdbc.update(copy.format("entrance_id", "'BM'", "NULL"))).isEqualTo(1)                // the control: an ordinary copy goes in

        // the unlock record: at or above its own threshold, of its own entrance's petition, and not rewritten
        val record = "INSERT INTO assembly.petition_unlock SELECT %s, %s, convened_by, demand_unmet_note, weighed_on, %s, threshold_pct, threshold_constant, " +
            "threshold_verified, law_version, engine_version, recorded_at, login_issuer, login_subject FROM assembly.petition_unlock WHERE petition_id = '$id'"
        assertThatThrownBy { jdbc.update(record.format("'$another'", "entrance_id", "19.99")) }
            .isInstanceOf(DataIntegrityViolationException::class.java).hasMessageContaining("petition_unlock_at_threshold")
        assertThatThrownBy { jdbc.update(record.format("'$another'", "'${entrance()}'", "held_pct")) }
            .isInstanceOf(DataIntegrityViolationException::class.java).hasMessageContaining("petition_unlock_petition_id_entrance_id_fkey")
        jdbc.update("UPDATE assembly.petition_unlock SET held_pct = 99 WHERE petition_id = ?::uuid", id)
        jdbc.update("DELETE FROM assembly.petition_unlock WHERE petition_id = ?::uuid", id)
        assertThat(jdbc.queryForObject("SELECT held_pct FROM assembly.petition_unlock WHERE petition_id = ?::uuid", java.math.BigDecimal::class.java, id))
            .isEqualByComparingTo("20")

        // what was signed is not rewritten under its signatures
        jdbc.update("UPDATE assembly.petition SET subject = 'Друго' WHERE id = ?::uuid", id)
        jdbc.update("DELETE FROM assembly.petition WHERE id = ?::uuid", another)
        assertThat(jdbc.queryForObject("SELECT subject FROM assembly.petition WHERE id = ?::uuid", String::class.java, id)).isEqualTo("Ремонт на покрива")

        // signatures are evidence
        jdbc.update("UPDATE assembly.petition_signature SET party_id = ? WHERE petition_id = ?::uuid", third, id)
        jdbc.update("DELETE FROM assembly.petition_signature WHERE petition_id = ?::uuid", id)
        assertThat(jdbc.queryForList("SELECT party_id FROM assembly.petition_signature WHERE petition_id = ?::uuid ORDER BY signed_at", UUID::class.java, id))
            .containsExactly(first, second)
        assertThatThrownBy { jdbc.execute("TRUNCATE assembly.petition_signature") }.hasMessageContaining("is not emptied")
        // another entrance does not see the petition
        mvc.perform(get("/api/assembly/entrances/${entrance()}/petitions/$id")).andExpect(status().isNotFound)
    }

    @Test
    fun `PM-SEC-001 a petition takes no signature from anyone but a signed-in owner of the entrance - what the audit could do is refused, and nothing is stored`() {
        val entranceId = entrance()
        val (first, second) = owned(entranceId).map { it.first }
        val base = "/api/assembly/entrances/$entranceId/petitions"
        val id = createdBy(loginOf(first), base, """{"subject":"Ремонт на покрива"}""").get("id").asText()

        // a login tied to no party, naming owners in the body — the audit's forged petition
        val nobody = "login-of-nobody-${UUID.randomUUID()}"
        asked(nobody, base, """{"openedBy":"$first","subject":"Подправена"}""").andExpect(status().isForbidden)
        asked(nobody, "$base/$id/signatures", """{"partyId":"$second"}""").andExpect(status().isForbidden)
        asked(nobody, "$base/$id/assembly", """{"convenedBy":"$first",${meeting.drop(1)}""").andExpect(status().isForbidden)
        // the manager of the entrance is not, as such, an owner; nor is an owner of another entrance
        val manager = party().also {
            jdbc.update(
                "INSERT INTO identity_org.management_mandate (id, entrance_id, body, party_id, valid_from, valid_to) VALUES (gen_random_uuid(), ?, 'BM', ?, current_date - 30, current_date + 300)",
                entranceId, it,
            )
        }
        asked(loginOf(manager), "$base/$id/signatures").andExpect(status().isForbidden)
        val elsewhere = entrance()
        val nextDoor = loginOf(owned(elsewhere).first().first)
        asked(nextDoor, "$base/$id/signatures").andExpect(status().isForbidden)
        // … and through their own entrance's path, where they are an owner, this petition is not there to sign or convene on
        asked(nextDoor, "/api/assembly/entrances/$elsewhere/petitions/$id/signatures").andExpect(status().isNotFound)
        asked(nextDoor, "/api/assembly/entrances/$elsewhere/petitions/$id/assembly", meeting).andExpect(status().isNotFound)
        // a former owner: the title ended yesterday
        val former = party().also {
            jdbc.update(
                "INSERT INTO registry.title (id, entrance_id, unit_id, party_id, title_role, share, valid_from, valid_to) " +
                    "SELECT gen_random_uuid(), entrance_id, unit_id, ?, 'OWN', 0.000001, current_date - 400, current_date - 1 FROM registry.title WHERE party_id = ?",
                it, first,
            )
        }
        asked(loginOf(former), "$base/$id/signatures").andExpect(status().isForbidden)
        // no token at all: sign-in is off in this run, and nobody is asking
        mvc.perform(post("$base/$id/signatures").contentType(MediaType.APPLICATION_JSON).content("""{"partyId":"$second"}""")).andExpect(status().isForbidden)

        assertThat(jdbc.queryForList("SELECT party_id FROM assembly.petition_signature WHERE petition_id = ?::uuid", UUID::class.java, id)).containsExactly(first)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM assembly.petition WHERE entrance_id = ?", Int::class.java, entranceId)).isEqualTo(1)
        // the signature names the sign-in that made it, and the table takes none that does not
        assertThat(jdbc.queryForMap("SELECT login_issuer, login_subject FROM assembly.petition_signature WHERE petition_id = ?::uuid", id))
            .containsEntry("login_issuer", realm).containsEntry("login_subject", "login-$first")
        assertThatThrownBy {
            jdbc.update(
                "INSERT INTO assembly.petition_signature (id, entrance_id, petition_id, party_id, signed_at) VALUES (gen_random_uuid(), ?, ?::uuid, ?, now())",
                entranceId, id, second,
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java).hasMessageContaining("petition_signature_names_its_login")
    }

    @Test
    fun `PM-GA-003 a unit that also has a holder of use is not weighed - the petition says why and nothing is convened`() {
        val entranceId = entrance()
        val owners = owned(entranceId)
        val (third, thirdUnit) = owners[2]                                           // 30%: past the threshold alone
        val base = "/api/assembly/entrances/$entranceId/petitions"
        val vera = loginOf(third)
        val id = createdBy(vera, base, """{"subject":"Ремонт на покрива"}""").get("id").asText()
        fun read() = json.readTree(mvc.perform(get("$base/$id")).andExpect(status().isOk).andReturn().response.contentAsString)
        assertThat(read().get("unlocked").asBoolean()).isTrue()                      // owners only: weighed in full

        title(entranceId, thirdUnit, party(), "USR")                                 // a right of use over the same unit
        val weighed = read()
        assertThat(weighed.get("heldPct").asText()).isEqualTo("0")                    // the unit is left out of the figure
        assertThat(weighed.get("unlocked").asBoolean()).isFalse()
        assertThat(weighed.get("cannotWeigh").single().asText()).contains(thirdUnit.toString()).contains("TODO(legal): PM-GA-003")
        val refusal = asked(vera, "$base/$id/assembly", meeting)
            .andExpect(status().isConflict).andReturn().response.contentAsString
        assertThat(json.readTree(refusal).get("error").asText()).contains("cannot be weighed").contains(thirdUnit.toString())
        assertThat(jdbc.queryForObject("SELECT count(*) FROM assembly.petition_unlock WHERE petition_id = ?::uuid", Int::class.java, id)).isZero()
    }
}
