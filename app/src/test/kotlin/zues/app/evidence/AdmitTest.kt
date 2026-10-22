package zues.app.evidence

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/** What a document must carry before anything is looked up — pure, no database. */
class AdmitTest {

    private val paper = PaperOriginal("архивът в стаята на УС", "председателят на УС")

    private fun request(kind: String?, paperOriginal: PaperOriginal? = paper, entityRef: String = "assembly:${UUID.randomUUID()}", content: ByteArray = byteArrayOf(1)) =
        StoreDocument(UUID.randomUUID(), entityRef, kind, "application/pdf", content, UUID.randomUUID(), paperOriginal)

    @Test
    fun `PM-DOC-002 untyped uploads are not accepted for statutory slots - each of the six kinds the rule names is, and nothing else`() {
        assertThat(DocumentKind.entries.map { admit(request(it.name)) }).containsExactlyElementsOf(DocumentKind.entries)
        assertThat(DocumentKind.entries.map { it.name })
            .containsExactlyInAnyOrder("PROTOCOL", "NOTICE", "POSTING_ACT", "CONSTATIVE_PROTOCOL", "FILING", "CERTIFICATE")
        for (untyped in listOf(null, "", "OTHER", "protocol", "SCAN")) {
            assertThatThrownBy { admit(request(untyped)) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("PM-DOC-002")
        }
    }

    @Test
    fun `PM-DOC-007 archive location is a mandatory field on protocols - a protocol names where its paper is and who keeps it`() {
        assertThatThrownBy { admit(request("PROTOCOL", paperOriginal = null)) }.hasMessageContaining("PM-DOC-007")
        assertThatThrownBy { admit(request("PROTOCOL", PaperOriginal(" ", "председателят на УС"))) }.hasMessageContaining("PM-DOC-007")
        assertThatThrownBy { admit(request("PROTOCOL", PaperOriginal("архивът", ""))) }.hasMessageContaining("PM-DOC-007")
        assertThat(admit(request("PROTOCOL"))).isEqualTo(DocumentKind.PROTOCOL)
        // a kind the law keeps no paper original of is stored without one
        assertThat(admit(request("NOTICE", paperOriginal = null))).isEqualTo(DocumentKind.NOTICE)
    }

    @Test
    fun `PM-DOC-001 a document names the entity it belongs to and has content`() {
        assertThatThrownBy { admit(request("NOTICE", entityRef = "")) }.hasMessageContaining("PM-DOC-001")
        assertThatThrownBy { admit(request("NOTICE", entityRef = "assembly")) }.hasMessageContaining("PM-DOC-001")
        assertThatThrownBy { admit(request("NOTICE", content = ByteArray(0))) }.hasMessageContaining("content")
    }
}
