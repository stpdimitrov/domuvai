package zues.app.registry

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
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
}
