package zues.app.intake

import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * The commit and revert endpoints with the service mocked — no database. Proves the routes bind
 * and return their shapes, and that a state conflict (committing an import that is not REPRODUCED)
 * surfaces as 409, not 400. The adoption itself is proved by the Docker-gated IT.
 */
@WebMvcTest(ImportController::class)
@org.springframework.context.annotation.Import(ImportGuard::class, zues.app.identity_org.WhoAsks::class, zues.app.policy.Policy::class, IntakeTestClock::class)
class ImportCommitWebTest {

    @org.springframework.test.context.bean.override.mockito.MockitoBean lateinit var logins: zues.app.identity_org.Logins

    @Autowired lateinit var mvc: MockMvc

    @MockitoBean lateinit var imports: ImportService

    private val importId = UUID.randomUUID()

    private val commitBody = """
        {"committedBy":"${UUID.randomUUID()}",
         "sheet":{"period":"2026-05","legalDate":"2026-05-01","lines":[],"csv":"designation,ideal_parts,occupants,fee_minor"}}
    """.trimIndent()

    @Test
    fun `POST commit answers that the commit is under way, with the row count, and GET shows why one was blocked`() {
        whenever(imports.find(importId))
            .thenReturn(ImportRow(importId, UUID.randomUUID(), "COMMIT_BLOCKED", "sha", 2, 0, 0, commitBlockedBy = "ideal parts sum to 200.0000"))
        mvc.perform(get("/api/intake/imports/$importId"))
            .andExpect(jsonPath("$.status").value("COMMIT_BLOCKED"))
            .andExpect(jsonPath("$.commitBlockedBy").value("ideal parts sum to 200.0000"))
            .andExpect(jsonPath("$.revertBlockedBy").doesNotExist())
        whenever(imports.commit(eq(importId), any(), any())).thenReturn(CommitResult(importId, 2, 0, emptyList()))
        mvc.perform(post("/api/intake/imports/$importId/commit").contentType(MediaType.APPLICATION_JSON).content(commitBody))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("COMMITTING"))
            .andExpect(jsonPath("$.rowsCreated").value(2))
            .andExpect(jsonPath("$.rowsChanged").value(0))
    }

    @Test
    fun `with sign-in switched off nobody is asking — the body says who commits and who reverts, and one that says nobody is a 400`() {
        whenever(imports.find(importId)).thenReturn(ImportRow(importId, UUID.randomUUID(), "REPRODUCED", "sha", 2, 0, 0))
        val stated = UUID.randomUUID()
        whenever(imports.commit(eq(importId), any(), any())).thenReturn(CommitResult(importId, 2, 0, emptyList()))
        mvc.perform(post("/api/intake/imports/$importId/commit").contentType(MediaType.APPLICATION_JSON)
            .content(commitBody.replace(Regex("\"committedBy\":\"[^\"]+\""), "\"committedBy\":\"$stated\""))).andExpect(status().is2xxSuccessful)
        org.mockito.kotlin.verify(imports).commit(eq(importId), eq(stated), any())
        mvc.perform(post("/api/intake/imports/$importId/commit").contentType(MediaType.APPLICATION_JSON)
            .content("""{"sheet":{"period":"2026-05","legalDate":"2026-05-01","lines":[],"csv":"designation,ideal_parts,occupants,fee_minor"}}""")).andExpect(status().isBadRequest)
        mvc.perform(post("/api/intake/imports/$importId/revert").contentType(MediaType.APPLICATION_JSON).content("""{"reason":"x"}""")).andExpect(status().isBadRequest)
    }

    @Test
    fun `committing an import in the wrong state is a 409`() {
        whenever(imports.find(importId)).thenReturn(ImportRow(importId, UUID.randomUUID(), "NEEDS_REVIEW", "sha", 2, 1, 0))
        whenever(imports.commit(any(), any(), any())).thenThrow(ImportStateException("import is NEEDS_REVIEW"))
        mvc.perform(post("/api/intake/imports/$importId/commit").contentType(MediaType.APPLICATION_JSON).content(commitBody))
            .andExpect(status().isConflict)
    }

    @Test
    fun `POST revert answers that the revert is under way, and GET shows what blocked one`() {
        whenever(imports.find(importId))
            .thenReturn(ImportRow(importId, UUID.randomUUID(), "REVERT_BLOCKED", "sha", 2, 0, 0, "title (title_unit_id_fkey)"))
        mvc.perform(get("/api/intake/imports/$importId"))
            .andExpect(jsonPath("$.status").value("REVERT_BLOCKED"))
            .andExpect(jsonPath("$.revertBlockedBy").value("title (title_unit_id_fkey)"))
        whenever(imports.revert(eq(importId), any(), any()))
            .thenReturn(ImportRow(importId, UUID.randomUUID(), "REVERTING", "sha", 2, 0, 0))
        mvc.perform(
            post("/api/intake/imports/$importId/revert").contentType(MediaType.APPLICATION_JSON)
                .content("""{"revertedBy":"${UUID.randomUUID()}","reason":"wrong entrance"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("REVERTING"))
            .andExpect(jsonPath("$.revertBlockedBy").doesNotExist())
    }
}
