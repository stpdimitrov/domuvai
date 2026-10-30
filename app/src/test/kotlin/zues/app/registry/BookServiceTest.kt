package zues.app.registry

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * The book assembled with the repositories mocked — no Spring, no database. A party carries an identity number here, so
 * the JSON the book is served as can be searched for it (PM-BOOK-011).
 */
class BookServiceTest {

    private val units: PropertyUnitRepository = mock()
    private val parties: PartyRepository = mock()
    private val titles: TitleRepository = mock()
    private val entrances: EntranceRepository = mock()
    private val ownership = OwnershipService(mock<JdbcAggregateTemplate>(), parties, titles, units, Clock.fixed(Instant.parse("2026-05-01T00:00:00Z"), ZoneOffset.UTC))
    private val service = BookService(units, ownership, mock(), mock(), entrances)

    private val entranceId = UUID.randomUUID()
    private val unitId = UUID.randomUUID()
    private val titleId = UUID.randomUUID()
    private val partyId = UUID.randomUUID()
    private val egn = "7501020018"

    private fun theBook(): CondominiumBook {
        whenever(entrances.existsById(entranceId)).thenReturn(true)
        whenever(units.findByEntranceId(entranceId)).thenReturn(
            listOf(PropertyUnit(unitId, entranceId, "ап. 1", "FLAT", null, BigDecimal("60.0000"), false)),
        )
        whenever(titles.findByEntranceId(entranceId)).thenReturn(
            listOf(Title(titleId, entranceId, unitId, partyId, "OWN", BigDecimal("1.000000"), LocalDate.of(2026, 1, 1), null)),
        )
        whenever(parties.findAllById(any<Iterable<UUID>>())).thenReturn(listOf(Party(partyId, "Иван Петров", "EGN", egn)))
        return service.forEntrance(entranceId, LocalDate.of(2026, 5, 1))
    }

    @Test
    fun `PM-BOOK-011 the book's parties carry their id and name — no identity number`() {
        val parties = theBook().units.single().parties
        val party = parties.single()
        assertThat(party.partyId).isEqualTo(partyId).isNotEqualTo(titleId).isNotEqualTo(unitId)
        assertThat(party.name).isEqualTo("Иван Петров")
        val json = ObjectMapper().writeValueAsString(parties)
        assertThat(json).contains(partyId.toString()).doesNotContain(egn).doesNotContain("idValue").doesNotContain("idType")
        assertThat(ObjectMapper().valueToTree<com.fasterxml.jackson.databind.JsonNode>(party).fieldNames().asSequence().toSet())
            .isEqualTo(setOf("partyId", "name", "role", "share"))
    }
}
