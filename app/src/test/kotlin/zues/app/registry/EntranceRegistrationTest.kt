package zues.app.registry

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import java.time.Clock
import java.util.UUID

/**
 * Registering an entrance with its collaborators mocked — no Spring, no database — so the building
 * rule is proved locally: a second entrance joins its building instead of founding another
 * (PM-ORG-001). That each entrance then keeps its own 100% is proved by RegistryUnitsPersistenceIT.
 */
class EntranceRegistrationTest {

    private val aggregates: JdbcAggregateTemplate = mock()
    private val service = RegistryService(aggregates, mock(), mock(), mock(), mock(), mock(), mock(), Clock.systemUTC())
    private val condominiumId = UUID.randomUUID()

    @BeforeEach
    fun echoInserts() {
        whenever(aggregates.insert(any<Any>())).thenAnswer { it.arguments[0] }
    }

    @Test
    fun `PM-ORG-001 a second entrance joins its building instead of founding another`() {
        whenever(aggregates.existsById(condominiumId, Condominium::class.java)).thenReturn(true)

        val created = service.registerEntrance(RegisterEntrance(null, "Б", "GA", condominiumId))

        val written = argumentCaptor<Any>()
        verify(aggregates).insert(written.capture())                     // the entrance only — no new building
        assertThat(written.firstValue).isInstanceOfSatisfying(Entrance::class.java) {
            assertThat(it.condominiumId).isEqualTo(condominiumId)
        }
        assertThat(created.condominiumId).isEqualTo(condominiumId)
    }

    @Test
    fun `a new building's first entrance founds it at its address`() {
        val created = service.registerEntrance(RegisterEntrance("ул. Шипка 14", "А", "GA"))

        val written = argumentCaptor<Any>()
        verify(aggregates, times(2)).insert(written.capture())
        val founded = written.firstValue as Condominium
        assertThat(founded.address).isEqualTo("ул. Шипка 14")
        assertThat(created.condominiumId).isEqualTo(founded.id)
    }

    @Test
    fun `an entrance names a new building's address or an existing building, never both or neither`() {
        assertThatThrownBy { service.registerEntrance(RegisterEntrance("ул. Шипка 14", "А", "GA", condominiumId)) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { service.registerEntrance(RegisterEntrance(null, "А", "GA")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `joining a building that does not exist is not found`() {
        whenever(aggregates.existsById(condominiumId, Condominium::class.java)).thenReturn(false)
        assertThatThrownBy { service.registerEntrance(RegisterEntrance(null, "Б", "GA", condominiumId)) }
            .isInstanceOf(NoSuchElementException::class.java)
    }
}
