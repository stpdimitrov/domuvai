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
 * book, that with no date it is read as of today in Sofia, that a read names who and why or is not
 * served (PM-BOOK-007), that the retention pass runs as of that day, and that a malformed `on` date
 * is a 400 (parsed before the service is ever called).
 */
@WebMvcTest(BookController::class)
class BookWebTest {

    @TestConfiguration
    class FixedClock {
        // 00:30 on 1 June in Sofia (UTC+3 in summer); still 31 May in UTC
        @Bean fun clock(): Clock = Clock.fixed(Instant.parse("2026-05-31T21:30:00Z"), ZoneOffset.UTC)
    }

    @Autowired lateinit var mvc: MockMvc

    @MockitoBean lateinit var access: BookAccessService
    @MockitoBean lateinit var retention: BookRetentionService

    private val entranceId = UUID.randomUUID()
    private val manager = UUID.randomUUID()

    private fun readBook() = get("/api/registry/entrances/$entranceId/book").param("actor", manager.toString()).param("purpose", "годишен отчет")

    @Test
    fun `PM-BOOK-001 the book is read back as the electronic record`() {
        whenever(access.read(eq(entranceId), any(), eq(manager), eq("годишен отчет"))).thenReturn(
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
        mvc.perform(readBook())
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.complete").value(true))
            .andExpect(jsonPath("$.units[0].designation").value("ап. 1"))
            .andExpect(jsonPath("$.units[0].complete").value(true))
    }

    @Test
    fun `PM-SYS-004 with no date given the book is read as of today in Sofia`() {
        whenever(access.read(eq(entranceId), any(), any(), any())).thenAnswer {
            CondominiumBook(entranceId, it.getArgument(1), emptyList(), complete = true)
        }
        mvc.perform(readBook())
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
        mvc.perform(readBook().param("on", "nonsense")).andExpect(status().isBadRequest)
    }

    @Test
    fun `PM-BOOK-007 the book is not served to a reader who does not say who reads and why`() {
        val url = "/api/registry/entrances/$entranceId/book"
        mvc.perform(get(url)).andExpect(status().isBadRequest)
        mvc.perform(get(url).param("actor", manager.toString())).andExpect(status().isBadRequest)
        mvc.perform(get(url).param("purpose", "годишен отчет")).andExpect(status().isBadRequest)
        mvc.perform(get(url).param("actor", "not-an-id").param("purpose", "годишен отчет")).andExpect(status().isBadRequest)
        mvc.perform(get("$url/access-log")).andExpect(status().isBadRequest)
        org.mockito.Mockito.verifyNoInteractions(access)
        // … and what the service refuses — a blank purpose, an actor who is not registered — is a 400 with its reason
        whenever(access.read(eq(entranceId), any(), eq(manager), eq(" "))).thenThrow(IllegalArgumentException("a purpose is required"))
        mvc.perform(get(url).param("actor", manager.toString()).param("purpose", " "))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("a purpose is required"))
        whenever(access.read(eq(entranceId), any(), eq(manager), eq("x"))).thenThrow(NoSuchElementException("no entrance"))
        mvc.perform(get(url).param("actor", manager.toString()).param("purpose", "x")).andExpect(status().isNotFound)
    }

    @Test
    fun `PM-BOOK-007 the access log is exported with who read, why and when`() {
        whenever(access.export(entranceId, manager, "проверка на КЗЛД")).thenReturn(
            listOf(
                BookAccessView(UUID.randomUUID(), manager, "Мария Иванова", "годишен отчет", "BOOK_READ", LocalDate.parse("2026-06-01"), Instant.parse("2026-05-31T21:30:00Z")),
                BookAccessView(UUID.randomUUID(), manager, "Мария Иванова", "проверка на КЗЛД", "LOG_EXPORT", null, Instant.parse("2026-05-31T21:31:00Z")),
            ),
        )
        mvc.perform(get("/api/registry/entrances/$entranceId/book/access-log").param("actor", manager.toString()).param("purpose", "проверка на КЗЛД"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].actorName").value("Мария Иванова"))
            .andExpect(jsonPath("$[0].kind").value("BOOK_READ"))
            .andExpect(jsonPath("$[0].bookDate").value("2026-06-01"))
            .andExpect(jsonPath("$[0].at").value("2026-05-31T21:30:00Z"))
            .andExpect(jsonPath("$[1].purpose").value("проверка на КЗЛД"))
    }
}
