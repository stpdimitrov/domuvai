package zues.app.intake

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import java.util.Optional
import java.util.UUID

/**
 * The import service with the repository and aggregate template mocked — no Spring, no database.
 * Proves the verdict and provenance recorded from the dry-run, and that a missing import is not
 * found. The persistence itself is proved end to end by the Docker-gated IT.
 */
class ImportServiceTest {

    private val aggregates: JdbcAggregateTemplate = mock()
    private val imports: ImportRepository = mock()
    private val service = ImportService(aggregates, imports)

    private val entranceId = UUID.randomUUID()

    private fun request(feeA: Long, feeB: Long) = FeeSheetDryRunRequest(
        period = "2026-05", legalDate = "2026-05-01",
        lines = listOf(TariffInput("MAINTENANCE", "BY_IDEAL_PARTS", "GA-2026-1", totalMinor = 10_000)),
        csv = "designation,ideal_parts,occupants,fee_minor\nап. 1,60.0000,2,$feeA\nап. 2,40.0000,1,$feeB",
    )

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
}
