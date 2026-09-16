package zues.app.money

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class IbanTest {

    @Test
    fun `a well-formed IBAN passes through, spaces stripped`() {
        assertThat(Iban.normalize("BG80 BNBG 9661 1020 3456 78")).isEqualTo("BG80BNBG96611020345678")
    }

    @Test
    fun `lower case is upper-cased`() {
        assertThat(Iban.normalize("bg80bnbg96611020345678")).isEqualTo("BG80BNBG96611020345678")
    }

    @Test
    fun `a value that is not IBAN-shaped is rejected`() {
        assertThatThrownBy { Iban.normalize("not-an-account") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `a too-short value is rejected`() {
        assertThatThrownBy { Iban.normalize("BG80BNBG") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
