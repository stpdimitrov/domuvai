package zues.app.registry

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * PM-BOOK-002's acceptance, proved without a database: a unit is book-complete only with ideal parts
 * recorded and at least one owner named. Everything else the book records (built area, household,
 * non-use) is reported but does not, by itself, gate completeness.
 */
class BookCompletenessTest {

    @Test
    fun `PM-BOOK-002 a unit is book-complete only with ideal parts and an owner name`() {
        assertThat(BookCompleteness.complete("60.0000", listOf("Иван Петров"))).isTrue()
        assertThat(BookCompleteness.complete("60.0000", emptyList())).isFalse()          // no owner named
        assertThat(BookCompleteness.complete(null, listOf("Иван Петров"))).isFalse()     // no ideal parts
        assertThat(BookCompleteness.complete("", listOf("Иван Петров"))).isFalse()       // blank ideal parts
    }
}
