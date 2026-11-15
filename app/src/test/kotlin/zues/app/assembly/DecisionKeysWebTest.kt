package zues.app.assembly

import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import zues.app.identity_org.WhoAsks
import java.time.LocalDate
import java.util.UUID

/**
 * The item close, the absentee merge and the protocol posting over HTTP (Rule: PM-SYS-014): each takes its
 * `Idempotency-Key` from the header and hands it to the act, a missing one is a 400 before the act is asked, a blank one
 * the act's 400, and a key used for another request the act's 409. What the act does with the key is [VotePersistenceIT]'s.
 */
@WebMvcTest(DecisionController::class, AbsenteeMergeController::class, ProtocolPostingController::class, properties = ["domuvai.auth.mode=off"])
@Import(WhoAsks::class)
class DecisionKeysWebTest {

    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var acts: DecisionActs
    @MockitoBean lateinit var merges: AbsenteeMergeService
    @MockitoBean lateinit var postings: ProtocolPostingService
    @MockitoBean lateinit var logins: zues.app.identity_org.Logins

    private val entrance = UUID.randomUUID()
    private val assembly = UUID.randomUUID()
    private val item = UUID.randomUUID()
    private val base = "/api/assembly/entrances/$entrance/assemblies/$assembly"
    private val writes = listOf("$base/agenda/$item/decision" to "", "$base/agenda/$item/absentee-merge" to "", "$base/protocol-posting" to """{"postedOn":"2026-03-14"}""")

    private fun send(path: String, body: String, key: String?) =
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body).also { r -> key?.let { r.header("Idempotency-Key", it) } })

    @Test
    fun `PM-SYS-014 an item's close, an absentee merge and a protocol posting without an Idempotency-Key are a 400 before the act is asked`() {
        writes.forEach { (path, body) -> send(path, body, null).andExpect(status().isBadRequest) }
        verifyNoInteractions(acts, merges, postings)
    }

    @Test
    fun `PM-SYS-014 the key in the header is the one each act is given - a blank one is a 400 and a key used for another request a 409`() {
        whenever(acts.close(any(), eq(entrance), eq(assembly), eq(item), eq("k-close"))).thenThrow(IdempotencyKeyReused("Idempotency-Key k-close was used for another request"))
        whenever(merges.merge(any(), eq(entrance), eq(assembly), eq(item), eq("k-merge"))).thenThrow(IdempotencyKeyReused("Idempotency-Key k-merge was used for another request"))
        whenever(postings.record(any(), eq(entrance), eq(assembly), eq("k-post"), eq(LocalDate.parse("2026-03-14")))).thenThrow(IdempotencyKeyReused("Idempotency-Key k-post was used for another request"))
        whenever(acts.close(any(), any(), any(), any(), eq(" "))).thenThrow(BlankIdempotencyKey("an action queued offline carries an idempotency key (PM-SYS-014)"))
        whenever(merges.merge(any(), any(), any(), any(), eq(" "))).thenThrow(BlankIdempotencyKey("an action queued offline carries an idempotency key (PM-SYS-014)"))
        whenever(postings.record(any(), any(), any(), eq(" "), any())).thenThrow(BlankIdempotencyKey("an action queued offline carries an idempotency key (PM-SYS-014)"))
        writes.zip(listOf("k-close", "k-merge", "k-post")).forEach { (write, key) ->
            val (path, body) = write
            send(path, body, key).andExpect(status().isConflict).andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Idempotency-Key $key")))
            send(path, body, " ").andExpect(status().isBadRequest)
        }
        verify(acts).close(any(), eq(entrance), eq(assembly), eq(item), eq("k-close"))
        verify(merges).merge(any(), eq(entrance), eq(assembly), eq(item), eq("k-merge"))
        verify(postings).record(any(), eq(entrance), eq(assembly), eq("k-post"), eq(LocalDate.parse("2026-03-14")))
    }
}
