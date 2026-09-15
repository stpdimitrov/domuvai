package zues.app.registry

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Pure tests of the registry's ideal-parts rule — no Spring, no database, so they run in
 * the gate pack everywhere and prove PM-ORG-002 directly.
 */
class UnitValidationTest {

    @Test
    fun `PM-ORG-002 a unit set summing to 99_98 percent is rejected with the delta shown`() {
        assertThatThrownBy {
            UnitValidation.requirePartsSumTo100(listOf("50.0000", "49.9800"))
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("99.980000")
    }

    @Test
    fun `PM-ORG-002 a unit set summing to exactly 100 percent is accepted`() {
        assertThatCode {
            UnitValidation.requirePartsSumTo100(listOf("60.0000", "40.0000"))
        }.doesNotThrowAnyException()
    }

    @Test
    fun `PM-ORG-002 ideal parts finer than the schema's four decimals are rejected`() {
        assertThatThrownBy {
            UnitValidation.requirePartsSumTo100(listOf("50.00005", "49.99995"))
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
