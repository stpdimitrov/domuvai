package zues.app.intake

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
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
import zues.app.registry.HouseholdMemberRepository
import zues.app.registry.ImportedUnit
import zues.app.registry.PartyRepository
import zues.app.registry.PropertyUnitRepository
import zues.app.registry.RegisterUnit
import zues.app.registry.RegistryService
import zues.app.registry.TitleRepository
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * The commit seam against real PostgreSQL, proved in two halves so no test depends on the async
 * hop between them:
 *  - the intake HTTP lifecycle: a reviewed import commits (its file's hash must match) and reverts,
 *    and its status transitions REPRODUCED → COMMITTING → COMMITTED → REVERTING → REVERTED — or COMMIT_BLOCKED /
 *    REVERT_BLOCKED, with why, when the registry cannot carry it out (S-41c, S-41d; these tests do wait
 *    on the async hop, by reading the import until it settles);
 *  - the registry's adoption (RegistryService.adoptImport, which the listener calls): units are
 *    created stamped with the import id, typed UNSPECIFIED until the pilot sheet, summing to 100%
 *    (PM-ORG-002) under their entrance (PM-ORG-001), idempotent on redelivery, dropped on revert.
 * The publish is proved by ImportServiceTest; the listener→adopt hop is one line; Spring Modulith
 * delivers the event between them (a Scenario end-to-end await is a follow-up). Docker-gated —
 * skips locally, runs in CI.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class ImportCommitPersistenceIT {

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
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var units: PropertyUnitRepository
    @Autowired lateinit var registry: RegistryService
    @Autowired lateinit var household: HouseholdMemberRepository
    @Autowired lateinit var titles: TitleRepository
    @Autowired lateinit var parties: PartyRepository
    @Autowired lateinit var savepoint: zues.app.registry.ImportSavepoint
    @Autowired lateinit var jdbc: org.springframework.jdbc.core.JdbcTemplate

    private val ON = LocalDate.parse("2026-05-01")

    private fun createEntrance(): UUID {
        val response = mvc.perform(
            post("/api/registry/entrances").contentType(MediaType.APPLICATION_JSON)
                .content("""{"address":"ул. Раковски 1","label":"А","managementForm":"GA"}"""),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return UUID.fromString(json.readTree(response).get("entranceId").asText())
    }

    // A reproduced sheet: 60/40 of a 10 000-minor maintenance total → 6000/4000, cent-exact.
    private val sheet = """{"period":"2026-05","legalDate":"2026-05-01",
        "lines":[{"stream":"MAINTENANCE","key":"BY_IDEAL_PARTS","decisionId":"GA-2026-1","totalMinor":10000}],
        "csv":"designation,ideal_parts,occupants,fee_minor\nап. 1,60.0000,2,6000\nап. 2,40.0000,1,4000"}"""

    private fun recordImport(entranceId: UUID): String {
        val created = mvc.perform(
            post("/api/intake/entrances/$entranceId/imports").contentType(MediaType.APPLICATION_JSON).content(sheet),
        ).andExpect(status().isCreated).andExpect(jsonPath("$.report.reproduced").value(true))
            .andReturn().response.contentAsString
        return json.readTree(created).get("importId").asText()
    }

    @Test
    fun `a reviewed import commits and reverts through its status lifecycle`() {
        val entranceId = createEntrance()
        val importId = recordImport(entranceId)

        mvc.perform(
            post("/api/intake/imports/$importId/commit").contentType(MediaType.APPLICATION_JSON)
                .content("""{"committedBy":"${UUID.randomUUID()}","sheet":$sheet}"""),
        ).andExpect(status().isOk).andExpect(jsonPath("$.rowsCreated").value(2))
            .andExpect(jsonPath("$.status").value("COMMITTING"))                        // asked for; the registry has yet to adopt

        assertThat(eventually(importId, "COMMITTED").has("commitBlockedBy")).isFalse()
        assertThat(units.findByImportId(UUID.fromString(importId))).hasSize(2)

        // Committing again is refused — a committed import is not re-committed (409).
        mvc.perform(
            post("/api/intake/imports/$importId/commit").contentType(MediaType.APPLICATION_JSON)
                .content("""{"committedBy":"${UUID.randomUUID()}","sheet":$sheet}"""),
        ).andExpect(status().isConflict)

        mvc.perform(
            post("/api/intake/imports/$importId/revert").contentType(MediaType.APPLICATION_JSON)
                .content("""{"revertedBy":"${UUID.randomUUID()}","reason":"pilot re-import"}"""),
        ).andExpect(status().isOk).andExpect(jsonPath("$.status").value("REVERTING"))   // asked for; the registry has yet to act

        assertThat(eventually(importId, "REVERTED").has("revertBlockedBy")).isFalse()
        assertThat(units.findByImportId(UUID.fromString(importId))).isEmpty()

        // S-41e — a commit is delivered at least once. Delivered again now, it finds no row of the import; the revert's
        // mark refuses it all the same, and nothing is adopted under a record that says REVERTED.
        val again = listOf(
            ImportedUnit(RegisterUnit(designation = "ап. 1", unitType = "UNSPECIFIED", idealParts = "60.0000"), occupants = 2),
            ImportedUnit(RegisterUnit(designation = "ап. 2", unitType = "UNSPECIFIED", idealParts = "40.0000"), occupants = 1),
        )
        assertThatThrownBy { savepoint.adopt(entranceId, UUID.fromString(importId), ON, again) }
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("was reverted")
        assertThat(units.findByEntranceId(entranceId)).isEmpty()
        assertThat(household.findByImportId(UUID.fromString(importId))).isEmpty()
        savepoint.remove(UUID.fromString(importId))                                     // a revert delivered again: nothing to do, one mark
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reverted_import WHERE import_id = ?::uuid", Long::class.java, importId)).isEqualTo(1)
        jdbc.update("DELETE FROM reverted_import WHERE import_id = ?::uuid", importId)  // ignored: the mark stays
        assertThatThrownBy { jdbc.execute("TRUNCATE reverted_import") }.isInstanceOf(org.springframework.dao.DataIntegrityViolationException::class.java)
        assertThatThrownBy { savepoint.adopt(entranceId, UUID.fromString(importId), ON, again) }.isInstanceOf(IllegalStateException::class.java)
        mvc.perform(get("/api/intake/imports/$importId")).andExpect(jsonPath("$.status").value("REVERTED"))
        mvc.perform(                                                                    // done once: not reverted again
            post("/api/intake/imports/$importId/revert").contentType(MediaType.APPLICATION_JSON)
                .content("""{"revertedBy":"${UUID.randomUUID()}","reason":"again"}"""),
        ).andExpect(status().isConflict)
    }

    @Test
    fun `a commit the registry cannot adopt ends COMMIT_BLOCKED saying why, adopts nothing, and succeeds once what refused it is gone`() {
        val entranceId = createEntrance()
        mvc.perform(                                                                    // the entrance already holds its units: 100%
            post("/api/registry/entrances/$entranceId/units").contentType(MediaType.APPLICATION_JSON)
                .content("""{"units":[{"designation":"ап. 9","unitType":"APARTMENT","idealParts":"100.0000"}]}"""),
        ).andExpect(status().isCreated)
        val importId = recordImport(entranceId)
        val id = UUID.fromString(importId)
        val commit = post("/api/intake/imports/$importId/commit").contentType(MediaType.APPLICATION_JSON)
            .content("""{"committedBy":"${UUID.randomUUID()}","sheet":$sheet}""")

        mvc.perform(commit).andExpect(status().isOk).andExpect(jsonPath("$.status").value("COMMITTING"))
        val blocked = eventually(importId, "COMMIT_BLOCKED")
        assertThat(blocked.get("commitBlockedBy").asText())                             // the schema's own deferred check, run inside the savepoint
            .contains("sum to 200.0000").contains("PM-ORG-002")
        assertThat(units.findByImportId(id)).isEmpty()                                  // nothing was adopted —
        assertThat(household.findByImportId(id)).isEmpty()                              // — not even what was written before the refusal
        assertThat(units.findByEntranceId(entranceId)).hasSize(1)
        mvc.perform(                                                                    // never committed: nothing to revert
            post("/api/intake/imports/$importId/revert").contentType(MediaType.APPLICATION_JSON)
                .content("""{"revertedBy":"${UUID.randomUUID()}","reason":"x"}"""),
        ).andExpect(status().isConflict)

        jdbc.update("DELETE FROM unit WHERE entrance_id = ? AND import_id IS NULL", entranceId)
        mvc.perform(commit).andExpect(status().isOk).andExpect(jsonPath("$.status").value("COMMITTING"))
        assertThat(eventually(importId, "COMMITTED").has("commitBlockedBy")).isFalse()
        assertThat(units.findByImportId(id)).hasSize(2)
        assertThat(household.findByImportId(id)).hasSize(3)
    }

    /** The import as `GET` shows it, once its status is [status] — the registry reacts after the request has answered. */
    private fun eventually(importId: String, status: String): com.fasterxml.jackson.databind.JsonNode {
        var seen = ""
        repeat(100) {
            val view = json.readTree(mvc.perform(get("/api/intake/imports/$importId")).andReturn().response.contentAsString)
            seen = view.get("status").asText()
            if (seen == status) return view
            Thread.sleep(100)
        }
        throw AssertionError("import $importId is $seen after 10 s, not $status")
    }

    @Test
    fun `a revert the registry cannot carry out ends REVERT_BLOCKED naming what blocks it, removes nothing, and succeeds once the blocker is gone`() {
        val entranceId = createEntrance()
        val importId = recordImport(entranceId)
        val id = UUID.fromString(importId)
        mvc.perform(
            post("/api/intake/imports/$importId/commit").contentType(MediaType.APPLICATION_JSON)
                .content("""{"committedBy":"${UUID.randomUUID()}","sheet":$sheet}"""),
        ).andExpect(status().isOk)
        eventually(importId, "COMMITTED")                                               // the adoption, too, follows the answer
        val adopted = units.findByImportId(id)
        assertThat(adopted).hasSize(2)
        assertThat(household.findByImportId(id)).hasSize(3)

        // A title recorded after the commit points at an imported unit, and is not the import's to drop.
        val later = json.readTree(
            mvc.perform(post("/api/registry/parties").contentType(MediaType.APPLICATION_JSON).content("""{"fullName":"Нов Собственик"}"""))
                .andExpect(status().isCreated).andReturn().response.contentAsString,
        ).get("partyId").asText()
        mvc.perform(
            post("/api/registry/entrances/$entranceId/units/${adopted.first().id}/titles").contentType(MediaType.APPLICATION_JSON)
                .content("""{"partyId":"$later","share":"1","titleRole":"OWN","validFrom":"2026-06-01"}"""),
        ).andExpect(status().isCreated)

        val revert = post("/api/intake/imports/$importId/revert").contentType(MediaType.APPLICATION_JSON)
            .content("""{"revertedBy":"${UUID.randomUUID()}","reason":"wrong entrance"}""")
        mvc.perform(revert).andExpect(status().isOk).andExpect(jsonPath("$.status").value("REVERTING"))
        val blocked = eventually(importId, "REVERT_BLOCKED")
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reverted_import WHERE import_id = ?::uuid", Long::class.java, importId))
            .isEqualTo(0)                                                               // the mark was written, then undone with the refused removal
        assertThat(blocked.get("revertBlockedBy").asText()).startsWith("title (")       // the table that still points at the unit
        assertThat(units.findByImportId(id)).hasSize(2)                                  // nothing was removed —
        assertThat(household.findByImportId(id)).hasSize(3)                              // — not even what went first

        jdbc.update("DELETE FROM title WHERE party_id = ?::uuid", later)                 // the blocker is withdrawn
        mvc.perform(revert).andExpect(status().isOk).andExpect(jsonPath("$.status").value("REVERTING"))
        assertThat(eventually(importId, "REVERTED").has("revertBlockedBy")).isFalse()
        assertThat(units.findByImportId(id)).isEmpty()
        assertThat(household.findByImportId(id)).isEmpty()
    }

    @Test
    fun `the registry adopts a committed import's units, idempotently, and drops them on revert (PM-ORG-001, PM-ORG-002)`() {
        val entranceId = createEntrance()
        val importId = UUID.randomUUID()
        val commands = listOf(
            RegisterUnit(designation = "об. 1", unitType = "UNSPECIFIED", idealParts = "70.0000"),
            RegisterUnit(designation = "об. 2", unitType = "UNSPECIFIED", idealParts = "30.0000"),
        )

        // adoptImport is the reaction the listener runs; called directly it is synchronous (the
        // @ApplicationModuleListener wrapper is @Async — its delivery is Spring Modulith's, not ours).
        assertThat(registry.adoptImport(entranceId, importId, ON, commands.map { ImportedUnit(it) })).hasSize(2)

        val adopted = units.findByEntranceId(entranceId)
        assertThat(adopted).hasSize(2)                                              // PM-ORG-001: under the entrance
        assertThat(adopted.map { it.designation }).containsExactlyInAnyOrder("об. 1", "об. 2")
        assertThat(adopted).allSatisfy { assertThat(it.importId).isEqualTo(importId) }
        assertThat(adopted).allSatisfy { assertThat(it.unitType).isEqualTo("UNSPECIFIED") }
        assertThat(adopted.map { it.idealPartsPct }.reduce(BigDecimal::add)).isEqualByComparingTo(BigDecimal("100.0000"))

        assertThat(registry.adoptImport(entranceId, importId, ON, commands.map { ImportedUnit(it) })).isEmpty()  // redelivery is idempotent
        assertThat(units.findByImportId(importId)).hasSize(2)

        registry.revertImport(importId)
        assertThat(units.findByImportId(importId)).isEmpty()
    }

    @Test
    fun `PM-BOOK-002 an import's household and owner are adopted stamped, and revert drops them before the units`() {
        val entranceId = createEntrance()
        val importId = UUID.randomUUID()
        registry.adoptImport(
            entranceId, importId, ON,
            listOf(
                ImportedUnit(
                    RegisterUnit(designation = "об. 1", unitType = "UNSPECIFIED", areaM2 = BigDecimal("72.50"), idealParts = "60.0000"),
                    occupants = 2, childrenUnder6 = 1, ownerName = "Иван Петров",
                ),
                ImportedUnit(RegisterUnit(designation = "об. 2", unitType = "UNSPECIFIED", idealParts = "40.0000")),
            ),
        )
        assertThat(units.findByImportId(importId).first { it.designation == "об. 1" }.areaM2).isEqualByComparingTo("72.50")
        assertThat(household.findByImportId(importId)).hasSize(3).filteredOn { it.isChildUnder6 }.hasSize(1)
        val owner = parties.findByImportId(importId).single()
        assertThat(owner.fullName).isEqualTo("Иван Петров")
        assertThat(owner.idValue).isNull()
        assertThat(titles.findByImportId(importId).single().partyId).isEqualTo(owner.id)

        registry.revertImport(importId)            // the foreign keys refuse this if the units go first
        assertThat(household.findByImportId(importId)).isEmpty()
        assertThat(titles.findByImportId(importId)).isEmpty()
        assertThat(parties.findByImportId(importId)).isEmpty()
        assertThat(units.findByImportId(importId)).isEmpty()
    }

    @Test
    fun `the registry refuses to adopt units that do not sum to 100 percent (PM-ORG-002)`() {
        val entranceId = createEntrance()
        assertThatThrownBy {
            registry.adoptImport(
                entranceId, UUID.randomUUID(), ON,
                listOf(
                    RegisterUnit(designation = "об. 1", unitType = "UNSPECIFIED", idealParts = "60.0000"),
                    RegisterUnit(designation = "об. 2", unitType = "UNSPECIFIED", idealParts = "30.0000"),   // 90%, not 100
                ).map { ImportedUnit(it) },
            )
        }.isInstanceOf(RuntimeException::class.java)
        assertThat(units.findByEntranceId(entranceId)).isEmpty()
    }

    @Test
    fun `PM-FEE-010 the units a commit adopts are charged what the dry-run charged — the business unit at the multiple`() {
        val multiple = zues.law.numberOn("BUSINESS_USE_MULTIPLIER_MAX", "2026-05-01").toInt()
        val lines = listOf(TariffInput("MAINTENANCE", "PER_UNIT", "GA-2026-1", rateMinor = 1_000))
        val sheet = FeeSheet.parse(
            "designation,ideal_parts,occupants,fee_minor,business\nоб. 1,60.0000,2,1000,\nоб. 2,40.0000,0,${1_000 * multiple},да",
        )
        // the dry-run reproduces the sheet: the business unit at the multiple, the other at the standard rate
        assertThat(IntakeDryRun.of("e", "2026-05", "2026-05-01", multiple, lines, sheet).reproduced).isTrue()

        // what the listener hands the registry for that sheet (ImportAdoption, proved by ImportAdoptionTest): business
        // use, no separate entrance. Called directly, as this class does throughout — the listener's delivery is async.
        val entranceId = createEntrance()
        registry.adoptImport(
            entranceId, UUID.randomUUID(), ON,
            sheet.rows.map { ImportedUnit(RegisterUnit(it.designation, "UNSPECIFIED", idealParts = it.idealParts, businessUse = it.businessUse)) },
        )

        val preview = mvc.perform(
            post("/api/money/entrances/$entranceId/charge-runs/preview").contentType(MediaType.APPLICATION_JSON).content(
                """{"period":"2026-05","legalDate":"2026-05-01","businessMultiplier":$multiple,
                    "lines":[{"stream":"MAINTENANCE","key":"PER_UNIT","decisionId":"GA-2026-1","rateMinor":1000}]}""",
            ),
        ).andExpect(status().isOk).andReturn().response.contentAsString
        val charged = json.readTree(preview).get("charges").associate { it.get("designation").asText() to it.get("totalMinor").asLong() }
        assertThat(charged).isEqualTo(sheet.rows.associate { it.designation to it.theirFeeMinor })
    }
}
