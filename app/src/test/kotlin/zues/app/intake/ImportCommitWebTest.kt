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
class ImportCommitWebTest {

    @Autowired lateinit var mvc: MockMvc

    @MockitoBean lateinit var imports: ImportService

    private val importId = UUID.randomUUID()

    private val commitBody = """
        {"committedBy":"${UUID.randomUUID()}",
         "sheet":{"period":"2026-05","legalDate":"2026-05-01","lines":[],"csv":"designation,ideal_parts,occupants,fee_minor"}}
    """.trimIndent()

    @Test
    fun `POST commit adopts the import and returns the row count`() {
        whenever(imports.commit(eq(importId), any(), any())).thenReturn(CommitResult(importId, 2, 0))
        mvc.perform(post("/api/intake/imports/$importId/commit").contentType(MediaType.APPLICATION_JSON).content(commitBody))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.rowsCreated").value(2))
            .andExpect(jsonPath("$.rowsChanged").value(0))
    }

    @Test
    fun `committing an import in the wrong state is a 409`() {
        whenever(imports.commit(any(), any(), any())).thenThrow(ImportStateException("import is NEEDS_REVIEW"))
        mvc.perform(post("/api/intake/imports/$importId/commit").contentType(MediaType.APPLICATION_JSON).content(commitBody))
            .andExpect(status().isConflict)
    }

    @Test
    fun `POST revert returns the reverted import`() {
        whenever(imports.revert(eq(importId), any(), any()))
            .thenReturn(ImportRow(importId, UUID.randomUUID(), "REVERTED", "sha", 2, 0, 0))
        mvc.perform(
            post("/api/intake/imports/$importId/revert").contentType(MediaType.APPLICATION_JSON)
                .content("""{"revertedBy":"${UUID.randomUUID()}","reason":"wrong entrance"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("REVERTED"))
    }
}
