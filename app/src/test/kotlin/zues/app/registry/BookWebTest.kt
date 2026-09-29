package zues.app.registry

import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * The book endpoints with the services mocked — no database. Proves the route binds and returns the
 * book, that with no date it is read as of today in Sofia, that the retention pass runs as of that
 * day, and that a malformed `on` date is a 400 (parsed before the service is ever called).
 */
@WebMvcTest(BookController::class)
class BookWebTest {

    @TestConfiguration
    class FixedClock {
        // 00:30 on 1 June in Sofia (UTC+3 in summer); still 31 May in UTC
        @Bean fun clock(): Clock = Clock.fixed(Instant.parse("2026-05-31T21:30:00Z"), ZoneOffset.UTC)
    }

    @Autowired lateinit var mvc: MockMvc

    @MockitoBean lateinit var book: BookService
    @MockitoBean lateinit var retention: BookRetentionService

    private val entranceId = UUID.randomUUID()

    @Test
    fun `PM-BOOK-001 the book is read back as the electronic record`() {
        whenever(book.forEntrance(eq(entranceId), any())).thenReturn(
            CondominiumBook(
                entranceId, LocalDate.parse("2026-06-01"),
                listOf(
                    BookUnitEntry(
                        UUID.randomUUID(), "ап. 1", "72.50", "60.0000",
                        listOf(BookParty("Иван Петров", "OWN", "1")), 2, emptyList(), complete = true,
                    ),
                ),
                complete = true,
            ),
        )
        mvc.perform(get("/api/registry/entrances/$entranceId/book"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.complete").value(true))
            .andExpect(jsonPath("$.units[0].designation").value("ап. 1"))
            .andExpect(jsonPath("$.units[0].complete").value(true))
    }

    @Test
    fun `PM-SYS-004 with no date given the book is read as of today in Sofia`() {
        whenever(book.forEntrance(eq(entranceId), any())).thenAnswer {
            CondominiumBook(entranceId, it.getArgument(1), emptyList(), complete = true)
        }
        mvc.perform(get("/api/registry/entrances/$entranceId/book"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.asOf").value("2026-06-01"))
    }

    @Test
    fun `PM-BOOK-010 POST retention anonymises what is due today in Sofia and says what it did`() {
        whenever(retention.anonymiseDue(entranceId, LocalDate.parse("2026-06-01"))).thenReturn(
            RetentionApplied(LocalDate.parse("2026-06-01"), householdUnlinked = 1, animalPassportsCleared = 2),
        )
        mvc.perform(post("/api/registry/entrances/$entranceId/book/retention").param("on", "2030-01-01"))  // ignored: no caller date
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.on").value("2026-06-01"))
            .andExpect(jsonPath("$.householdUnlinked").value(1))
            .andExpect(jsonPath("$.animalPassportsCleared").value(2))
    }

    @Test
    fun `a malformed on date is a 400`() {
        mvc.perform(get("/api/registry/entrances/$entranceId/book").param("on", "nonsense"))
            .andExpect(status().isBadRequest)
    }
}
