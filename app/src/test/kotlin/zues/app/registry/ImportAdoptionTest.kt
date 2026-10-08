package zues.app.registry

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import zues.app.intake.AdoptedUnit
import zues.app.intake.ImportCommitApplied
import zues.app.intake.ImportCommitBlocked
import zues.app.intake.ImportCommitted
import zues.app.intake.ImportRevertApplied
import zues.app.intake.ImportRevertBlocked
import zues.app.intake.ImportReverted
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * The registry's adoption of a committed import (S-41b) with its collaborators mocked — no Spring,
 * no database — so what a commit creates is proved locally: the household behind each unit, its
 * owner, the provenance stamps, and the order a revert drops them in. The real writes, the
 * migration's columns and the foreign keys are proved by ImportCommitPersistenceIT (Docker-gated).
 */
class ImportAdoptionTest {

    private val aggregates: JdbcAggregateTemplate = mock()
    private val entrances: EntranceRepository = mock()
    private val units: PropertyUnitRepository = mock()
    private val household: HouseholdMemberRepository = mock()
    private val titles: TitleRepository = mock()
    private val parties: PartyRepository = mock()
    private val service = RegistryService(aggregates, entrances, units, household, titles, parties, mock(), Clock.systemUTC())

    private val entranceId = UUID.randomUUID()
    private val importId = UUID.randomUUID()
    private val on = LocalDate.parse("2026-05-01")

    private fun unit(designation: String, idealParts: String) =
        RegisterUnit(designation = designation, unitType = "UNSPECIFIED", idealParts = idealParts)

    /** Adopt [rows] and return every record the registry wrote, in order. */
    private fun adopt(vararg rows: ImportedUnit): List<Any> {
        whenever(entrances.existsById(entranceId)).thenReturn(true)
        whenever(units.findByImportId(importId)).thenReturn(emptyList())
        val written = argumentCaptor<Any>()
        whenever(aggregates.insert(written.capture())).thenAnswer { it.arguments[0] }
        service.adoptImport(entranceId, importId, on, rows.toList())
        return written.allValues
    }

    @Test
    fun `PM-FEE-008 PM-FEE-005 the persons charged are adopted as household members, children on top and flagged`() {
        val members = adopt(ImportedUnit(unit("ап. 1", "100.0000"), occupants = 2, childrenUnder6 = 1))
            .filterIsInstance<HouseholdMember>()
        assertThat(members).hasSize(3)
        assertThat(members.count { it.isChildUnder6 }).isEqualTo(1)             // PM-FEE-005: flagged, never charged
        assertThat(members).allSatisfy {
            assertThat(it.partyId).isNull()                                      // a count names no one
            assertThat(it.validFrom).isEqualTo(on)
            assertThat(it.importId).isEqualTo(importId)
        }
    }

    @Test
    fun `PM-ORG-011 PM-BOOK-011 an adopted owner is a name-only party holding a title from the import's legal date`() {
        val written = adopt(
            ImportedUnit(unit("ап. 1", "60.0000"), ownerName = "Иван Петров"),
            ImportedUnit(unit("ап. 2", "40.0000"), ownerName = "Иван Петров"),   // the same name, perhaps another person
        )
        val owners = written.filterIsInstance<Party>()
        assertThat(owners).hasSize(2)                                            // never merged across rows
        assertThat(owners).allSatisfy {
            assertThat(it.fullName).isEqualTo("Иван Петров")
            assertThat(it.idType).isNull()                                       // PM-BOOK-011: no identity number to expose
            assertThat(it.idValue).isNull()
            assertThat(it.importId).isEqualTo(importId)
        }
        val held = written.filterIsInstance<Title>()
        assertThat(held.map { it.partyId }).containsExactlyElementsOf(owners.map { it.id })
        assertThat(held).allSatisfy {
            assertThat(it.titleRole).isEqualTo(TitleRole.OWN.name)
            assertThat(it.share).isEqualByComparingTo(BigDecimal.ONE)
            assertThat(it.validFrom).isEqualTo(on)                               // PM-ORG-011: effective-dated
            assertThat(it.importId).isEqualTo(importId)
        }
    }

    @Test
    fun `PM-ORG-009 a registered unit keeps its business use and its separate entrance as given — neither sets the other`() {
        whenever(entrances.existsById(entranceId)).thenReturn(true)
        val written = argumentCaptor<Any>()
        whenever(aggregates.insert(written.capture())).thenAnswer { it.arguments[0] }
        val facts = listOf(true to false, true to true, false to true, false to false)   // business use to separate entrance

        service.registerUnits(
            entranceId,
            facts.mapIndexed { i, (business, entrance) ->
                RegisterUnit("обект ${i + 1}", "FLAT", idealParts = "25.0000", separateEntrance = entrance, businessUse = business)
            },
        )

        assertThat(written.allValues.filterIsInstance<PropertyUnit>().map { it.businessUse to it.separateEntrance }).isEqualTo(facts)
    }

    @Test
    fun `PM-ORG-009 an adopted unit carries the business use it was given, and no separate entrance`() {
        val adopted = adopt(
            ImportedUnit(unit("магазин", "60.0000").copy(businessUse = true)),
            ImportedUnit(unit("ап. 1", "40.0000")),
        ).filterIsInstance<PropertyUnit>()
        assertThat(adopted.map { Triple(it.designation, it.businessUse, it.separateEntrance) })
            .containsExactly(Triple("магазин", true, false), Triple("ап. 1", false, false))
    }

    @Test
    fun `PM-FEE-010 the import listener hands the sheet's business use to the registry, with no separate entrance`() {
        whenever(entrances.existsById(entranceId)).thenReturn(true)
        whenever(units.findByImportId(importId)).thenReturn(emptyList())
        val written = argumentCaptor<Any>()
        whenever(aggregates.insert(written.capture())).thenAnswer { it.arguments[0] }

        ImportAdoption(service, ImportSavepoint(service, mock()), mock()).on(     // the real savepoint step, its SQL mocked
            ImportCommitted(
                entranceId, importId, importId, UUID.randomUUID(), 2, 0,
                listOf(
                    AdoptedUnit("магазин", "40.0000", null, 0, 0, null, businessUse = true),
                    AdoptedUnit("ап. 1", "60.0000", null, 2, 0, null),
                ),
                on,
            ),
        )

        assertThat(written.allValues.filterIsInstance<PropertyUnit>().map { Triple(it.designation, it.businessUse, it.separateEntrance) })
            .containsExactly(Triple("магазин", true, false), Triple("ап. 1", false, false))
    }

    @Test
    fun `a sheet row with no household and no owner adopts the unit alone`() {
        val written = adopt(ImportedUnit(unit("ап. 1", "100.0000")))
        assertThat(written).hasSize(1).allSatisfy { assertThat(it).isInstanceOf(PropertyUnit::class.java) }
    }

    @Test
    fun `revert drops the rows that point at a unit before the units themselves`() {
        val unitId = UUID.randomUUID()
        val partyId = UUID.randomUUID()
        val member = HouseholdMember(UUID.randomUUID(), entranceId, unitId, null, false, on, null, importId)
        val title = Title(UUID.randomUUID(), entranceId, unitId, partyId, "OWN", BigDecimal.ONE, on, null, importId)
        val party = Party(partyId, "Иван Петров", null, null, importId)
        val adopted = PropertyUnit(unitId, entranceId, "ап. 1", "UNSPECIFIED", null, BigDecimal("100.0000"), false, importId)
        whenever(household.findByImportId(importId)).thenReturn(listOf(member))
        whenever(titles.findByImportId(importId)).thenReturn(listOf(title))
        whenever(parties.findByImportId(importId)).thenReturn(listOf(party))
        whenever(units.findByImportId(importId)).thenReturn(listOf(adopted))

        service.revertImport(importId)

        inOrder(household, titles, parties, units) {
            verify(household).deleteAll(listOf(member))
            verify(titles).deleteAll(listOf(title))
            verify(parties).deleteAll(listOf(party))
            verify(units).deleteAll(listOf(adopted))
        }
    }

    // S-41c — the reaction to a revert reports back what became of it, always.

    private val removal: ImportSavepoint = mock()
    private val published: org.springframework.context.ApplicationEventPublisher = mock()

    private fun reverted() = ImportReverted(entranceId, importId, UUID.randomUUID(), java.time.Instant.parse("2026-05-02T08:00:00Z"), "wrong entrance")

    /** A batched delete's refusal as it arrives: wrapped twice, the database's own at the root. */
    private fun refusal(message: String, state: org.postgresql.util.PSQLState) =
        RuntimeException("Failed to execute BatchWithValue", org.springframework.dao.DataIntegrityViolationException("refused", org.postgresql.util.PSQLException(message, state)))

    private fun answerTo(failure: RuntimeException?): Any {
        org.mockito.Mockito.reset(removal, published)
        if (failure != null) org.mockito.kotlin.doThrow(failure).whenever(removal).remove(importId)
        ImportAdoption(service, removal, published).on(reverted())
        val answer = argumentCaptor<Any>()
        verify(published).publishEvent(answer.capture())
        return answer.firstValue
    }

    @Test
    fun `a revert the registry carried out is answered with ImportRevertApplied`() {
        assertThat(answerTo(null)).isEqualTo(ImportRevertApplied(entranceId, importId))
        verify(removal).remove(importId)
    }

    @Test
    fun `a revert refused because a later record still points at an imported row is answered with what blocks it`() {
        val fk = """ERROR: update or delete on table "unit" violates foreign key constraint "title_unit_id_fkey" on table "title"
  Detail: Key (id)=(20e8955b) is still referenced from table "title"."""
        assertThat(answerTo(refusal(fk, org.postgresql.util.PSQLState.FOREIGN_KEY_VIOLATION)))
            .isEqualTo(ImportRevertBlocked(entranceId, importId, "title (title_unit_id_fkey)"))
    }

    @Test
    fun `a revert that fails for any other reason is answered too — with the database's own line, or the failure's kind — never left waiting`() {
        val parts = "ERROR: ideal parts for entrance 7f sum to 40.0000, must be 100.0000 or empty (PM-ORG-002)\n  Where: PL/pgSQL function"
        assertThat(answerTo(refusal(parts, org.postgresql.util.PSQLState.UNKNOWN_STATE)))
            .isEqualTo(ImportRevertBlocked(entranceId, importId, "ideal parts for entrance 7f sum to 40.0000, must be 100.0000 or empty (PM-ORG-002)"))
        assertThat(answerTo(IllegalStateException("not the database at all")))
            .isEqualTo(ImportRevertBlocked(entranceId, importId, "not the database at all"))
        assertThat(answerTo(IllegalStateException()))
            .isEqualTo(ImportRevertBlocked(entranceId, importId, "the registry could not carry it out (IllegalStateException)"))
        assertThat((answerTo(refusal("ERROR: " + "x".repeat(400), org.postgresql.util.PSQLState.UNKNOWN_STATE)) as ImportRevertBlocked).blockedBy).hasSize(300)
    }

    // S-41d — and so does the reaction to a commit.

    private fun committed() = ImportCommitted(entranceId, importId, importId, UUID.randomUUID(), 1, 0, listOf(AdoptedUnit("ап. 1", "100.0000", "72.50", 2, 1, "Иван Петров")), on)

    private fun commitAnswerTo(failure: RuntimeException?): Any {
        org.mockito.Mockito.reset(removal, published)
        if (failure != null) org.mockito.kotlin.doThrow(failure).whenever(removal).adopt(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any())
        ImportAdoption(service, removal, published).on(committed())
        val answer = argumentCaptor<Any>()
        verify(published).publishEvent(answer.capture())
        return answer.firstValue
    }

    @Test
    fun `a commit the registry adopted is answered with ImportCommitApplied, and each field of the sheet reaches the adoption`() {
        assertThat(commitAnswerTo(null)).isEqualTo(ImportCommitApplied(entranceId, importId))
        val adopted = argumentCaptor<List<ImportedUnit>>()
        verify(removal).adopt(org.mockito.kotlin.eq(entranceId), org.mockito.kotlin.eq(importId), org.mockito.kotlin.eq(on), adopted.capture())
        val row = adopted.firstValue.single()
        assertThat(listOf(row.unit.designation, row.unit.unitType, row.unit.idealParts, row.unit.areaM2, row.occupants, row.childrenUnder6, row.ownerName))
            .containsExactly("ап. 1", "UNSPECIFIED", "100.0000", BigDecimal("72.50"), 2, 1, "Иван Петров")
    }

    @Test
    fun `a commit the registry could not adopt is answered with why — the database's own line, what the failure said, or its kind`() {
        val parts = "ERROR: ideal parts for entrance 7f sum to 200.0000, must be 100.0000 or empty (PM-ORG-002)\n  Where: PL/pgSQL function"
        assertThat(commitAnswerTo(refusal(parts, org.postgresql.util.PSQLState.UNKNOWN_STATE)))
            .isEqualTo(ImportCommitBlocked(entranceId, importId, "ideal parts for entrance 7f sum to 200.0000, must be 100.0000 or empty (PM-ORG-002)"))
        assertThat(commitAnswerTo(NoSuchElementException("no entrance $entranceId")))
            .isEqualTo(ImportCommitBlocked(entranceId, importId, "no entrance $entranceId"))
        assertThat(commitAnswerTo(IllegalStateException()))
            .isEqualTo(ImportCommitBlocked(entranceId, importId, "the registry could not carry it out (IllegalStateException)"))
        // a framework's failure can quote the row it was writing — it is named by its kind, never by its message (PM-BOOK-011)
        assertThat(commitAnswerTo(org.springframework.dao.OptimisticLockingFailureException("Failed to update Party(fullName=Иван Петров)")))
            .isEqualTo(ImportCommitBlocked(entranceId, importId, "the registry could not carry it out (OptimisticLockingFailureException)"))
        assertThat(commitAnswerTo(RuntimeException("Failed to execute InsertRoot(entity=Party(fullName=Иван Петров))", IllegalStateException("no converter"))))
            .isEqualTo(ImportCommitBlocked(entranceId, importId, "the registry could not carry it out (RuntimeException)"))
        // a foreign key refusing an insert is not "a later record": only a revert names what still points at a row
        val fk = """ERROR: insert or update on table "unit" violates foreign key constraint "unit_entrance_id_fkey""""
        assertThat(commitAnswerTo(refusal(fk, org.postgresql.util.PSQLState.FOREIGN_KEY_VIOLATION)))
            .isEqualTo(ImportCommitBlocked(entranceId, importId, """insert or update on table "unit" violates foreign key constraint "unit_entrance_id_fkey""""))
    }
}
