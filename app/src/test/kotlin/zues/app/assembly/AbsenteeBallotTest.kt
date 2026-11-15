package zues.app.assembly

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** An absentee ballot's window and form, pure. */
class AbsenteeBallotTest {

    @Test
    fun `PM-GA-017 a ballot is received from the sitting to day 7 after it and entered by then - rolled off a weekend`() {
        assertThatCode { withinAbsenteeWindow("2026-11-02", "2026-11-02", "2026-11-02") }.doesNotThrowAnyException()   // the sitting's day, after the close
        assertThatCode { withinAbsenteeWindow("2026-11-02", "2026-11-09", "2026-11-09") }.doesNotThrowAnyException()   // day 7, a Monday
        assertThatThrownBy { withinAbsenteeWindow("2026-11-02", "2026-11-10", "2026-11-10") }.isInstanceOf(IllegalArgumentException::class.java)   // day 8
        assertThatThrownBy { withinAbsenteeWindow("2026-11-02", "2026-11-01", "2026-11-03") }.isInstanceOf(IllegalArgumentException::class.java)   // before the sitting
        assertThatThrownBy { withinAbsenteeWindow("2026-11-02", "2026-11-05", "2026-11-04") }.isInstanceOf(IllegalArgumentException::class.java)   // not received yet
        assertThatThrownBy { withinAbsenteeWindow("2026-11-02", "2026-11-09", "2026-11-10") }.isInstanceOf(IllegalStateException::class.java)      // received in time, entered late
        assertThatCode { withinAbsenteeWindow("2026-11-14", "2026-11-23", "2026-11-23") }.doesNotThrowAnyException()   // day 7 a Saturday → Monday
    }

    @Test
    fun `PM-GA-018 only a handwritten-signed declaration with its scan's hash is a ballot - a QES waits for its provider`() {
        val hash = "0f".repeat(32)
        assertThatCode { signedDeclaration("HANDWRITTEN", "декларация 7", hash) }.doesNotThrowAnyException()
        assertThatThrownBy { signedDeclaration("QES", "декларация 7", hash) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { signedDeclaration("CLICK", "декларация 7", hash) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { signedDeclaration("HANDWRITTEN", " ", hash) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { signedDeclaration("HANDWRITTEN", "декларация 7", hash.uppercase()) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
