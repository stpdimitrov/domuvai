package zues.app.registry

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

/**
 * Every "today" the registry stamps or falls back to is the Europe/Sofia calendar day (PM-SYS-004),
 * proved at 00:30 in Sofia — still the evening before in UTC, the zone the app's clock runs in. The
 * services run with their repositories mocked — no Spring, no database. The book's own default is
 * proved by BookWebTest, the declarations' by DeclarationServiceTest and DeclarationWebTest.
 */
class LegalTodayTest {

    private val aggregates: JdbcAggregateTemplate = mock()
    private val units: PropertyUnitRepository = mock()
    private val parties: PartyRepository = mock()
    private val lateNight = Clock.fixed(Instant.parse("2026-06-16T21:30:00Z"), ZoneOffset.UTC)   // 00:30 on 17 June in Sofia (UTC+3 in summer)
    private val sofiaDay = LocalDate.parse("2026-06-17")
    private val registry = RegistryService(aggregates, mock(), units, mock(), mock(), parties, mock(), lateNight)
    private val ownership = OwnershipService(aggregates, parties, mock(), units, lateNight)

    private val entranceId = UUID.randomUUID()
    private val unit = PropertyUnit(UUID.randomUUID(), entranceId, "ап. 1", "FLAT", null, BigDecimal("100.0000"), false)

    @BeforeEach
    fun stubUnitAndInserts() {
        whenever(units.findById(unit.id)).thenReturn(Optional.of(unit))
        whenever(aggregates.insert(any<Any>())).thenAnswer { it.arguments[0] }
    }

    private fun inserted(): List<Any> =
        argumentCaptor<Any>().apply { verify(aggregates, atLeastOnce()).insert(capture()) }.allValues

    @Test
    fun `PM-SYS-004 an absence filed just after midnight in Sofia is dated the Sofia day, not the UTC one`() {
        registry.registerAbsence(entranceId, unit.id, listOf(RegisterAbsence("2026-07-01", "2026-08-15")))

        val filed = inserted().filterIsInstance<AbsenceDeclaration>().single()
        assertThat(filed.filedOn).isEqualTo(sofiaDay)          // PM-FEE-007 judges the filing's timeliness on this day
    }

    @Test
    fun `PM-SYS-004 a resident or an animal given no start date starts on the Sofia day`() {
        registry.registerHousehold(entranceId, unit.id, listOf(RegisterMember()))
        registry.registerAnimals(entranceId, unit.id, listOf(RegisterAnimal("DOG")))

        val rows = inserted()
        assertThat(rows.filterIsInstance<HouseholdMember>().single().validFrom).isEqualTo(sofiaDay)
        assertThat(rows.filterIsInstance<Animal>().single().validFrom).isEqualTo(sofiaDay)
    }

    @Test
    fun `PM-SYS-004 a title given no start date starts on the Sofia day`() {
        val partyId = UUID.randomUUID()
        whenever(parties.existsById(partyId)).thenReturn(true)

        ownership.assignTitle(entranceId, unit.id, AssignTitle(partyId, "OWN"))

        assertThat(inserted().filterIsInstance<Title>().single().validFrom).isEqualTo(sofiaDay)
    }
}
