package zues.app.registry

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import zues.law.constantOn
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

/**
 * Book declarations with the repositories mocked — no Spring, no database. Proves who owes a
 * declaration as of a date (PM-BOOK-003) — the statutory window through the one deadline utility,
 * so a deadline on a weekend rolls to the next working day — and that a filing records the system's
 * date and the template in force (PM-BOOK-004). The table itself is proved by BookPersistenceIT.
 */
class DeclarationServiceTest {

    private val aggregates: JdbcAggregateTemplate = mock()
    private val declarations: BookDeclarationRepository = mock()
    private val units: PropertyUnitRepository = mock()
    private val titles: TitleRepository = mock()
    private val parties: PartyRepository = mock()
    private val clock = Clock.fixed(Instant.parse("2026-06-10T09:00:00Z"), ZoneOffset.UTC)
    private val service = DeclarationService(aggregates, declarations, units, titles, parties, clock)

    private val entranceId = UUID.randomUUID()
    private val unit = PropertyUnit(UUID.randomUUID(), entranceId, "ап. 1", "FLAT", null, BigDecimal("100.0000"), false)
    private val owner = Party(UUID.randomUUID(), "Мария Георгиева", null, null)

    private fun owns(from: String) =
        Title(UUID.randomUUID(), entranceId, unit.id, owner.id, "OWN", BigDecimal.ONE, LocalDate.parse(from), null)

    private fun filed(on: String) =
        BookDeclaration(UUID.randomUUID(), entranceId, unit.id, owner.id, "ACQUISITION", LocalDate.parse(on), "ministry-template-1")

    private fun holding(title: Title, vararg filings: BookDeclaration) {
        whenever(titles.findByEntranceId(entranceId)).thenReturn(listOf(title))
        whenever(declarations.findByEntranceId(entranceId)).thenReturn(filings.toList())
        whenever(units.findByEntranceId(entranceId)).thenReturn(listOf(unit))
        whenever(parties.findAllById(any())).thenReturn(listOf(owner))
    }

    private fun overdueOn(day: String) = service.overdue(entranceId, LocalDate.parse(day))

    @Test
    fun `PM-BOOK-003 day 16 without a declaration is overdue when day 15 is a working day`() {
        holding(owns("2026-06-01"))                            // acquired Monday 1 June; day 15 is Tuesday 16 June
        assertThat(overdueOn("2026-06-16")).isEmpty()          // the last day to file
        val owed = overdueOn("2026-06-17").single()            // day 16
        assertThat(owed.partyName).isEqualTo("Мария Георгиева")
        assertThat(owed.designation).isEqualTo("ап. 1")
        assertThat(owed.dueOn).isEqualTo(LocalDate.parse("2026-06-16"))
    }

    @Test
    fun `PM-BOOK-003 a deadline that lands on a weekend rolls to the next working day`() {
        holding(owns("2026-05-01"))                            // day 15 is Saturday 16 May
        assertThat(overdueOn("2026-05-17")).isEmpty()          // day 16 is a Sunday: the deadline rolled on
        assertThat(overdueOn("2026-05-18")).isEmpty()          // Monday 18 May — still in time
        assertThat(overdueOn("2026-05-19").single().dueOn).isEqualTo(LocalDate.parse("2026-05-18"))
    }

    @Test
    fun `PM-BOOK-003 only a declaration filed between acquisition and the date asked clears it`() {
        holding(owns("2026-06-01"), filed("2026-05-20"))       // filed before this title was acquired
        assertThat(overdueOn("2026-06-17")).hasSize(1)

        holding(owns("2026-06-01"), filed("2026-06-20"))       // filed late
        assertThat(overdueOn("2026-06-17")).hasSize(1)         // on the 17th it was still owed — history holds
        assertThat(overdueOn("2026-06-21")).isEmpty()          // once filed, it is filed
    }

    @Test
    fun `PM-BOOK-004 a declaration records the system's filing day and the template in force that day`() {
        whenever(units.findById(unit.id)).thenReturn(Optional.of(unit))
        whenever(parties.existsById(owner.id)).thenReturn(true)
        whenever(aggregates.insert(any<BookDeclaration>())).thenAnswer { it.arguments[0] }

        val declaration = service.file(entranceId, unit.id, owner.id, "ACQUISITION")

        assertThat(declaration.filedOn).isEqualTo(LocalDate.parse("2026-06-10"))    // the system's date, not the caller's
        assertThat(declaration.templateVersion).isEqualTo(constantOn("BOOK_DECLARATION_TEMPLATE", "2026-06-10").value)
    }

    @Test
    fun `PM-SYS-004 a filing just after midnight in Sofia is dated the Sofia day, not the UTC one`() {
        val lateNight = DeclarationService(
            aggregates, declarations, units, titles, parties, Clock.fixed(Instant.parse("2026-06-16T21:30:00Z"), ZoneOffset.UTC),
        )                                                       // 00:30 on 17 June in Sofia (UTC+3 in summer)
        whenever(units.findById(unit.id)).thenReturn(Optional.of(unit))
        whenever(parties.existsById(owner.id)).thenReturn(true)
        whenever(aggregates.insert(any<BookDeclaration>())).thenAnswer { it.arguments[0] }

        assertThat(lateNight.file(entranceId, unit.id, owner.id, "ACQUISITION").filedOn).isEqualTo(LocalDate.parse("2026-06-17"))
    }

    @Test
    fun `an unknown kind is refused, and a unit of another entrance is not found`() {
        whenever(units.findById(unit.id)).thenReturn(Optional.of(unit))
        whenever(parties.existsById(owner.id)).thenReturn(true)
        assertThatThrownBy { service.file(entranceId, unit.id, owner.id, "WHIM") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { service.file(UUID.randomUUID(), unit.id, owner.id, "ACQUISITION") }
            .isInstanceOf(NoSuchElementException::class.java)
    }
}
