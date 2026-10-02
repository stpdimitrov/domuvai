package zues.app.money

import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.util.UUID

/** The HTTP edge of the ledger's reads, with the service mocked: routing, the wire shape, and how a bad request surfaces. */
@WebMvcTest(LedgerController::class)
class LedgerWebTest {

    @Autowired lateinit var mvc: MockMvc

    @MockitoBean lateinit var ledger: LedgerReads

    private val entranceId = UUID.randomUUID()
    private val from = LocalDate.parse("2026-09-01")
    private val to = LocalDate.parse("2026-09-30")

    @Test
    fun `PM-FUND-005 GET the journal returns each journal with its date, its source and its signed legs`() {
        val journalId = UUID.randomUUID()
        val unit = UUID.randomUUID()
        whenever(ledger.journal(eq(entranceId), eq(from), eq(to), isNull())).thenReturn(
            JournalView(
                entranceId, from, to, null,
                listOf(JournalEntry(journalId, from, JournalSource.PAYMENT, listOf(JournalLeg("BANK:OPERATING", null, 4_000), JournalLeg("RECEIVABLE", unit, -4_000)))),
            ),
        )
        mvc.perform(get("/api/money/entrances/$entranceId/journal").param("from", "2026-09-01").param("to", "2026-09-30"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.journals[0].journalId").value(journalId.toString()))
            .andExpect(jsonPath("$.journals[0].valueDate").value("2026-09-01"))
            .andExpect(jsonPath("$.journals[0].source").value("PAYMENT"))
            .andExpect(jsonPath("$.journals[0].legs[0].account").value("BANK:OPERATING"))
            .andExpect(jsonPath("$.journals[0].legs[0].amountMinor").value(4000))
            .andExpect(jsonPath("$.journals[0].legs[1].unitId").value(unit.toString()))
            .andExpect(jsonPath("$.journals[0].legs[1].amountMinor").value(-4000))
    }

    @Test
    fun `PM-FUND-005 GET the journal passes the account asked for, and a blank one as none`() {
        whenever(ledger.journal(eq(entranceId), eq(from), eq(to), eq("BANK:REPAIR_RENEWAL")))
            .thenReturn(JournalView(entranceId, from, to, "BANK:REPAIR_RENEWAL", emptyList()))
        whenever(ledger.journal(eq(entranceId), eq(from), eq(to), isNull())).thenReturn(JournalView(entranceId, from, to, null, emptyList()))

        mvc.perform(get("/api/money/entrances/$entranceId/journal").param("from", "2026-09-01").param("to", "2026-09-30").param("account", "BANK:REPAIR_RENEWAL"))
            .andExpect(status().isOk).andExpect(jsonPath("$.account").value("BANK:REPAIR_RENEWAL"))
        mvc.perform(get("/api/money/entrances/$entranceId/journal").param("from", "2026-09-01").param("to", "2026-09-30").param("account", " "))
            .andExpect(status().isOk).andExpect(jsonPath("$.entranceId").value(entranceId.toString())).andExpect(jsonPath("$.account").doesNotExist())
    }

    @Test
    fun `a date that is not one, a missing one, a year no calendar holds, or a range the service refuses is a 400`() {
        mvc.perform(get("/api/money/entrances/$entranceId/journal").param("from", "-999999999-01-01").param("to", "2026-09-30"))
            .andExpect(status().isBadRequest)
        mvc.perform(get("/api/money/entrances/$entranceId/journal").param("from", "01.09.2026").param("to", "2026-09-30"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.error").value("from must be an ISO date (YYYY-MM-DD): 01.09.2026"))
        mvc.perform(get("/api/money/entrances/$entranceId/journal").param("from", "2026-09-01")).andExpect(status().isBadRequest)
        whenever(ledger.journal(any(), any(), any(), isNull())).thenThrow(IllegalArgumentException("to (2026-09-01) must not be before from (2026-09-30)"))
        mvc.perform(get("/api/money/entrances/$entranceId/journal").param("from", "2026-09-30").param("to", "2026-09-01"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `PM-FUND-004 GET the operating account returns its balance and that outflows are not recorded — none registered is a 404`() {
        whenever(ledger.operatingAccount(entranceId)).thenReturn(OperatingAccountView(entranceId, "BG22OPER", "Мария Иванова", 6_500, outflowsRecorded = false))
        mvc.perform(get("/api/money/entrances/$entranceId/operating-account"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.iban").value("BG22OPER"))
            .andExpect(jsonPath("$.holderName").value("Мария Иванова"))
            .andExpect(jsonPath("$.balanceMinor").value(6500))
            .andExpect(jsonPath("$.outflowsRecorded").value(false))

        val none = UUID.randomUUID()
        whenever(ledger.operatingAccount(none)).thenThrow(NoSuchElementException("entrance $none has no operating account registered"))
        mvc.perform(get("/api/money/entrances/$none/operating-account")).andExpect(status().isNotFound)
    }
}
