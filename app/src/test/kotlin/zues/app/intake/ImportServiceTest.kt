package zues.app.intake

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import zues.law.numberOn
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

/**
 * The import service with the repository, aggregate template and event publisher mocked — no
 * Spring, no database. Proves the recorded verdict (S-34), and the commit/revert lifecycle: the
 * status transitions, the guards that refuse a commit of the wrong import or the wrong file, and
 * the outbox events the registry adopts. The registry write itself is proved by the Docker-gated IT.
 */
class ImportServiceTest {

    private val aggregates: JdbcAggregateTemplate = mock()
    private val imports: ImportRepository = mock()
    private val events: ApplicationEventPublisher = mock()
    private val clock: Clock = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC)
    private val service = ImportService(aggregates, imports, events, clock)

    private val entranceId = UUID.randomUUID()
    private val committedBy = UUID.randomUUID()
    private val revertedBy = UUID.randomUUID()

    private fun request(feeA: Long, feeB: Long) = FeeSheetDryRunRequest(
        period = "2026-05", legalDate = "2026-05-01",
        lines = listOf(TariffInput("MAINTENANCE", "BY_IDEAL_PARTS", "GA-2026-1", totalMinor = 10_000)),
        csv = "designation,ideal_parts,occupants,fee_minor\nап. 1,60.0000,2,$feeA\nап. 2,40.0000,1,$feeB",
    )

    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Test
    fun `a reproduced import is recorded as REPRODUCED with the source hash`() {
        val captor = argumentCaptor<ImportRow>()
        whenever(aggregates.insert(captor.capture())).thenAnswer { it.getArgument<ImportRow>(0) }
        val result = service.record(entranceId, request(6000, 4000))   // 60/40 of 10000
        assertThat(result.report.reproduced).isTrue()
        assertThat(captor.firstValue.status).isEqualTo("REPRODUCED")
        assertThat(captor.firstValue.sourceSha).hasSize(64)             // sha-256 as hex
    }

    @Test
    fun `PM-FEE-011 a sheet whose concierge line is on another key than maintenance's is recorded NEEDS_REVIEW`() {
        val captor = argumentCaptor<ImportRow>()
        whenever(aggregates.insert(captor.capture())).thenAnswer { it.getArgument<ImportRow>(0) }
        val req = request(6500, 4500).let {
            it.copy(lines = it.lines + TariffInput("MAINTENANCE", "PER_UNIT", "GA-2026-2", rateMinor = 500, item = "CONCIERGE"))
        }

        val result = service.record(entranceId, req)   // the figures add up: unnamed, the line would reproduce

        assertThat(result.report.reproduced).isFalse()
        assertThat(captor.firstValue.status).isEqualTo("NEEDS_REVIEW")
        assertThat(captor.firstValue.violations).isEqualTo(1)
    }

    @Test
    fun `record honours a confirmed mapping for a header the profiler cannot recognise`() {
        val captor = argumentCaptor<ImportRow>()
        whenever(aggregates.insert(captor.capture())).thenAnswer { it.getArgument<ImportRow>(0) }
        val req = FeeSheetDryRunRequest(
            period = "2026-05", legalDate = "2026-05-01",
            lines = listOf(TariffInput("MAINTENANCE", "BY_IDEAL_PARTS", "GA-2026-1", totalMinor = 10_000)),
            csv = "col_a,col_b,col_c,col_d\nап. 1,60.0000,2,6000\nап. 2,40.0000,1,4000",
            mapping = mapOf(
                "col_a" to IntakeField.DESIGNATION, "col_b" to IntakeField.IDEAL_PARTS,
                "col_c" to IntakeField.OCCUPANTS, "col_d" to IntakeField.FEE_MINOR,
            ),
        )
        // without the mapping these headers are unrecognised (missing every required column); with it,
        // the sheet parses and reproduces to the cent — so this proves the mapping threads through record.
        val result = service.record(entranceId, req)
        assertThat(result.report.reproduced).isTrue()
        assertThat(captor.firstValue.status).isEqualTo("REPRODUCED")
    }

    @Test
    fun `an import that does not reconcile is recorded as NEEDS_REVIEW`() {
        val captor = argumentCaptor<ImportRow>()
        whenever(aggregates.insert(captor.capture())).thenAnswer { it.getArgument<ImportRow>(0) }
        service.record(entranceId, request(6001, 4000))                // one cent off
        assertThat(captor.firstValue.status).isEqualTo("NEEDS_REVIEW")
        assertThat(captor.firstValue.differing).isEqualTo(1)
    }

    @Test
    fun `a missing import is not found`() {
        whenever(imports.findById(any())).thenReturn(Optional.empty())
        assertThatThrownBy { service.find(UUID.randomUUID()) }.isInstanceOf(NoSuchElementException::class.java)
    }

    @Test
    fun `PM-FEE-011 a commit whose concierge line is on another key than maintenance's is refused, and says why`() {
        // Recorded REPRODUCED with the line unnamed; the commit names it. The figures still add up — the rule does not hold.
        val req = request(6500, 4500).let {
            it.copy(lines = it.lines + TariffInput("MAINTENANCE", "PER_UNIT", "GA-2026-2", rateMinor = 500, item = "CONCIERGE"))
        }
        val importId = UUID.randomUUID()
        whenever(imports.findById(importId))
            .thenReturn(Optional.of(ImportRow(importId, entranceId, "REPRODUCED", sha256(req.csv), 2, 0, 0)))

        assertThatThrownBy { service.commit(importId, committedBy, req) }
            .isInstanceOf(ImportStateException::class.java).hasMessageContaining("PM-FEE-011")
        verifyNoInteractions(aggregates, events)
    }

    @Test
    fun `committing a reproduced import adopts its units and publishes ImportCommitted`() {
        val req = request(6000, 4000)
        val importId = UUID.randomUUID()
        whenever(imports.findById(importId))
            .thenReturn(Optional.of(ImportRow(importId, entranceId, "REPRODUCED", sha256(req.csv), 2, 0, 0)))
        whenever(aggregates.update(any<ImportRow>())).thenAnswer { it.getArgument<ImportRow>(0) }

        val result = service.commit(importId, committedBy, req)

        assertThat(result.rowsCreated).isEqualTo(2)
        val updated = argumentCaptor<ImportRow>()
        verify(aggregates).update(updated.capture())
        assertThat(updated.firstValue.status).isEqualTo("COMMITTED")
        val event = argumentCaptor<ImportCommitted>()
        verify(events).publishEvent(event.capture())
        assertThat(event.firstValue.units.map { it.designation }).containsExactly("ап. 1", "ап. 2")
        assertThat(event.firstValue.units.map { it.idealParts }).containsExactly("60.0000", "40.0000")
        assertThat(event.firstValue.committedBy).isEqualTo(committedBy)
        assertThat(event.firstValue.rowsCreated).isEqualTo(2)
    }

    // Every optional column mapped: area, owner, children and business use are adopted; absence and
    // animals cannot be — each needs a declaration a count is not. ап. 2 is a business: it pays the multiple.
    private val multiple = numberOn("BUSINESS_USE_MULTIPLIER_MIN", "2026-05-01").toInt()   // read, never typed
    private fun commitRich(): Pair<CommitResult, ImportCommitted> {
        val req = FeeSheetDryRunRequest(
            period = "2026-05", legalDate = "2026-05-01", businessMultiplier = multiple,
            lines = listOf(TariffInput("MAINTENANCE", "PER_UNIT", "GA-2026-1", rateMinor = 1_000)),
            csv = "designation,ideal_parts,occupants,fee_minor,area,owner,children,absent_days,animals,business\n" +
                "ап. 1,60.0000,2,1000,72.50,Иван Петров,1,45,2,\n" +
                "ап. 2,40.0000,1,${1_000 * multiple},,,,,,да",
        )
        val importId = UUID.randomUUID()
        whenever(imports.findById(importId))
            .thenReturn(Optional.of(ImportRow(importId, entranceId, "REPRODUCED", sha256(req.csv), 2, 0, 0)))
        whenever(aggregates.update(any<ImportRow>())).thenAnswer { it.getArgument<ImportRow>(0) }
        val result = service.commit(importId, committedBy, req)
        val event = argumentCaptor<ImportCommitted>()
        verify(events).publishEvent(event.capture())
        return result to event.firstValue
    }

    @Test
    fun `PM-BOOK-002 a commit carries each unit's built area, household and owner to the registry`() {
        val (_, event) = commitRich()
        val (first, second) = event.units
        assertThat(first.builtArea).isEqualTo("72.50")
        assertThat(first.ownerName).isEqualTo("Иван Петров")
        assertThat(first.occupants).isEqualTo(2)
        assertThat(first.childrenUnder6).isEqualTo(1)
        assertThat(second.builtArea).isNull()                                // a blank cell is absent, never zero
        assertThat(second.ownerName).isNull()
        assertThat(second.childrenUnder6).isZero()
        assertThat(event.effectiveFrom).isEqualTo(LocalDate.parse("2026-05-01"))   // the import's legal date
    }

    @Test
    fun `PM-FEE-007 PM-BOOK-005 an absence or animal count is returned for a declaration, never adopted`() {
        val (result, _) = commitRich()
        assertThat(result.manualEntries).containsExactly(
            ManualEntry("ап. 1", IntakeField.ABSENT_DAYS, "45", "PM-FEE-007"),
            ManualEntry("ап. 1", IntakeField.ANIMALS, "2", "PM-BOOK-005"),
        )
    }

    @Test
    fun `PM-FEE-010 a commit carries the sheet's business use to the registry — it is no longer a manual entry`() {
        val (result, event) = commitRich()
        assertThat(event.units.map { it.designation to it.businessUse }).containsExactly("ап. 1" to false, "ап. 2" to true)
        assertThat(result.manualEntries.map { it.field }).doesNotContain(IntakeField.BUSINESS_USE)
    }

    @Test
    fun `PM-FEE-010 a commit whose sheet has a business unit and no multiple is refused, and says why`() {
        val req = FeeSheetDryRunRequest(
            period = "2026-05", legalDate = "2026-05-01",
            lines = listOf(TariffInput("MAINTENANCE", "PER_UNIT", "GA-2026-1", rateMinor = 1_000)),
            csv = "designation,ideal_parts,occupants,fee_minor,business\nап. 1,60.0000,2,1000,\nап. 2,40.0000,1,${1_000 * multiple},да",
        )
        val importId = UUID.randomUUID()
        whenever(imports.findById(importId))
            .thenReturn(Optional.of(ImportRow(importId, entranceId, "REPRODUCED", sha256(req.csv), 2, 0, 0)))

        assertThatThrownBy { service.commit(importId, committedBy, req) }
            .isInstanceOf(ImportStateException::class.java).hasMessageContaining("PM-FEE-010")
        verifyNoInteractions(aggregates, events)
    }

    @Test
    fun `an import that is not REPRODUCED cannot be committed`() {
        val importId = UUID.randomUUID()
        whenever(imports.findById(importId))
            .thenReturn(Optional.of(ImportRow(importId, entranceId, "NEEDS_REVIEW", "sha", 2, 1, 0)))
        assertThatThrownBy { service.commit(importId, committedBy, request(6001, 4000)) }
            .isInstanceOf(ImportStateException::class.java)
        verifyNoInteractions(events)
    }

    @Test
    fun `committing a sheet whose hash differs from the reviewed import is refused`() {
        val importId = UUID.randomUUID()
        whenever(imports.findById(importId))
            .thenReturn(Optional.of(ImportRow(importId, entranceId, "REPRODUCED", "a-different-hash", 2, 0, 0)))
        assertThatThrownBy { service.commit(importId, committedBy, request(6000, 4000)) }
            .isInstanceOf(ImportStateException::class.java)
        verifyNoInteractions(events)
    }

    @Test
    fun `reverting a committed import publishes ImportReverted with its reason, and waits as REVERTING`() {
        val importId = UUID.randomUUID()
        whenever(imports.findById(importId))
            .thenReturn(Optional.of(ImportRow(importId, entranceId, "COMMITTED", "sha", 2, 0, 0)))
        whenever(aggregates.update(any<ImportRow>())).thenAnswer { it.getArgument<ImportRow>(0) }

        val result = service.revert(importId, revertedBy, "wrong entrance")

        assertThat(result.status).isEqualTo("REVERTING")                 // the registry has not removed anything yet
        val event = argumentCaptor<ImportReverted>()
        verify(events).publishEvent(event.capture())
        assertThat(event.firstValue.reason).isEqualTo("wrong entrance")
        assertThat(event.firstValue.revertedBy).isEqualTo(revertedBy)
    }

    @Test
    fun `only a committed import, or one whose revert was blocked, can be reverted`() {
        val importId = UUID.randomUUID()
        for (status in listOf("REPRODUCED", "NEEDS_REVIEW", "REVERTING", "REVERTED")) {       // not twice, and not while one is under way
            whenever(imports.findById(importId)).thenReturn(Optional.of(ImportRow(importId, entranceId, status, "sha", 2, 0, 0)))
            assertThatThrownBy { service.revert(importId, revertedBy, "x") }.isInstanceOf(ImportStateException::class.java)
        }
        verifyNoInteractions(events)

        whenever(imports.findById(importId))
            .thenReturn(Optional.of(ImportRow(importId, entranceId, "REVERT_BLOCKED", "sha", 2, 0, 0, "title (title_unit_id_fkey)")))
        whenever(aggregates.update(any<ImportRow>())).thenAnswer { it.getArgument<ImportRow>(0) }
        val again = service.revert(importId, revertedBy, "the later title was withdrawn")
        assertThat(again.status).isEqualTo("REVERTING")
        assertThat(again.revertBlockedBy).isNull()                                           // asked again: the old answer is gone
        verify(events).publishEvent(any<ImportReverted>())
    }

    @Test
    fun `the registry's answer settles a REVERTING import — reverted, or blocked and by what — and no other`() {
        val importId = UUID.randomUUID()
        val updated = argumentCaptor<ImportRow>()
        whenever(aggregates.update(updated.capture())).thenAnswer { it.getArgument<ImportRow>(0) }
        whenever(imports.findById(importId)).thenReturn(Optional.of(ImportRow(importId, entranceId, "REVERTING", "sha", 2, 0, 0)))

        service.revertApplied(importId)
        service.revertBlocked(importId, "title (title_unit_id_fkey)")
        assertThat(updated.allValues.map { it.status to it.revertBlockedBy })
            .containsExactly("REVERTED" to null, "REVERT_BLOCKED" to "title (title_unit_id_fkey)")

        // Delivery is at least once: an answer for an import that is not waiting for one changes nothing.
        for (status in listOf("COMMITTED", "REVERTED", "REVERT_BLOCKED", "REPRODUCED")) {
            whenever(imports.findById(importId)).thenReturn(Optional.of(ImportRow(importId, entranceId, status, "sha", 2, 0, 0)))
            service.revertApplied(importId)
            service.revertBlocked(importId, "x")
        }
        whenever(imports.findById(importId)).thenReturn(Optional.empty())
        service.revertApplied(importId)
        assertThat(updated.allValues).hasSize(2)
    }
}
