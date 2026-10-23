package zues.app.evidence

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/** What a document must carry before anything is looked up — pure, no database. */
class AdmitTest {

    private val paper = PaperOriginal("архивът в стаята на УС", "председателят на УС")

    private fun request(
        kind: String?,
        paperOriginal: PaperOriginal? = paper,
        entityRef: String = "assembly:${UUID.randomUUID()}",
        content: ByteArray = byteArrayOf(1),
        breach: BreachRecord? = null,
        photoMetadata: PhotoMetadata? = null,
    ) =
        StoreDocument(UUID.randomUUID(), entityRef, kind, "application/pdf", content, UUID.randomUUID(), paperOriginal, null, null, null, photoMetadata, breach)

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

    @Test
    fun `PM-DOC-003 PM-DOC-004 constative protocol records the breach - who, what, when, where`() {
        val breach = BreachRecord(UUID.randomUUID(), "ползване на общи части за лични нужди", java.time.Instant.now(), "entrance 1, floor 3")
        // a constative protocol without breach record is rejected
        assertThatThrownBy { admit(request("CONSTATIVE_PROTOCOL", breach = null)) }
            .hasMessageContaining("PM-DOC-003").hasMessageContaining("PM-DOC-004")
        // each required field (description, location) is checked
        assertThatThrownBy { admit(request("CONSTATIVE_PROTOCOL", breach = breach.copy(description = " "))) }
            .hasMessageContaining("PM-DOC-004").hasMessageContaining("what the breach was")
        assertThatThrownBy { admit(request("CONSTATIVE_PROTOCOL", breach = breach.copy(location = ""))) }
            .hasMessageContaining("PM-DOC-004").hasMessageContaining("where the breach occurred")
        // a complete constative protocol is admitted
        assertThat(admit(request("CONSTATIVE_PROTOCOL", breach = breach))).isEqualTo(DocumentKind.CONSTATIVE_PROTOCOL)
        // other kinds do not require breach
        assertThat(admit(request("NOTICE", breach = null))).isEqualTo(DocumentKind.NOTICE)
    }

    @Test
    fun `PM-DOC-009 photographic evidence retains its capture timestamp and display copy hash`() {
        val captured = java.time.Instant.parse("2026-10-22T14:30:00Z")
        val displayHash = "a".repeat(64)
        val photo = PhotoMetadata(captured, displayHash)
        // a photo with valid metadata is admitted
        assertThat(admit(request("POSTING_ACT", photoMetadata = photo))).isEqualTo(DocumentKind.POSTING_ACT)
        // display copy hash must be a valid SHA-256 hash if present
        assertThatThrownBy { admit(request("POSTING_ACT", photoMetadata = PhotoMetadata(captured, "not-a-hash"))) }
            .hasMessageContaining("SHA-256")
        // null display copy is allowed (no resized version yet)
        assertThat(admit(request("POSTING_ACT", photoMetadata = PhotoMetadata(captured, null)))).isEqualTo(DocumentKind.POSTING_ACT)
    }
}
