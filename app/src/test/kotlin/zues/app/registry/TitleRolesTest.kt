package zues.app.registry

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import zues.app.policy.Asking
import zues.app.policy.Held
import zues.app.policy.Role
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** Roles from titles: an owner or a user of the units held on the date asked about, in that entrance, and nobody else. */
class TitleRolesTest {

    private val titles: TitleRepository = mock()
    private val roles = TitleRoles(titles)
    private val block = UUID.randomUUID()
    private val ivan = UUID.randomUUID()
    private val flat1 = UUID.randomUUID()
    private val flat2 = UUID.randomUUID()
    private val garage = UUID.randomUUID()

    private fun title(unit: UUID, party: UUID, role: String, from: String, to: String? = null, at: UUID = block) =
        Title(UUID.randomUUID(), at, unit, party, role, BigDecimal.ONE, LocalDate.parse(from), to?.let { LocalDate.parse(it) })

    private fun held(party: UUID?, on: String, at: UUID = block) = roles.held(Asking(null, party), at, LocalDate.parse(on))

    @Test
    fun `PM-ORG-011 a party is the owner of the units they hold a title in on the date — from its first day, up to and not including its last`() {
        whenever(titles.findByEntranceId(block)).thenReturn(
            listOf(
                title(flat1, ivan, "OWN", "2020-01-01", "2025-06-14"),                            // sold on the 14th
                title(flat2, ivan, "OWN", "2024-01-01"),
                title(garage, ivan, "USR", "2024-01-01"),
                title(flat1, UUID.randomUUID(), "OWN", "2025-06-14"),                             // the buyer
            ),
        )
        assertThat(held(ivan, "2019-12-31")).isEmpty()
        assertThat(held(ivan, "2020-01-01")).containsExactly(Held(Role.OWN, block, setOf(flat1)))
        assertThat(held(ivan, "2025-06-13")).containsExactlyInAnyOrder(Held(Role.OWN, block, setOf(flat1, flat2)), Held(Role.USR, block, setOf(garage)))
        assertThat(held(ivan, "2025-06-14")).containsExactlyInAnyOrder(Held(Role.OWN, block, setOf(flat2)), Held(Role.USR, block, setOf(garage)))
    }

    @Test
    fun `PM-ORG-011 another party's title, another entrance's and a login tied to no party are no role`() {
        val another = UUID.randomUUID()
        whenever(titles.findByEntranceId(block)).thenReturn(listOf(title(flat1, ivan, "OWN", "2020-01-01")))
        whenever(titles.findByEntranceId(another)).thenReturn(listOf(title(flat1, ivan, "OWN", "2020-01-01", at = block)))   // a careless read
        assertThat(held(UUID.randomUUID(), "2026-01-01")).isEmpty()
        assertThat(held(null, "2026-01-01")).isEmpty()
        assertThat(held(ivan, "2026-01-01", at = another)).isEmpty()
    }
}
