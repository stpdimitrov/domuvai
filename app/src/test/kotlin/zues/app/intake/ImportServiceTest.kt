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
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
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
    fun `reverting a committed import publishes ImportReverted with its reason`() {
        val importId = UUID.randomUUID()
        whenever(imports.findById(importId))
            .thenReturn(Optional.of(ImportRow(importId, entranceId, "COMMITTED", "sha", 2, 0, 0)))
        whenever(aggregates.update(any<ImportRow>())).thenAnswer { it.getArgument<ImportRow>(0) }

        val result = service.revert(importId, revertedBy, "wrong entrance")

        assertThat(result.status).isEqualTo("REVERTED")
        val event = argumentCaptor<ImportReverted>()
        verify(events).publishEvent(event.capture())
        assertThat(event.firstValue.reason).isEqualTo("wrong entrance")
        assertThat(event.firstValue.revertedBy).isEqualTo(revertedBy)
    }

    @Test
    fun `only a committed import can be reverted`() {
        val importId = UUID.randomUUID()
        whenever(imports.findById(importId))
            .thenReturn(Optional.of(ImportRow(importId, entranceId, "REPRODUCED", "sha", 2, 0, 0)))
        assertThatThrownBy { service.revert(importId, revertedBy, "x") }.isInstanceOf(ImportStateException::class.java)
        verifyNoInteractions(events)
    }
}
