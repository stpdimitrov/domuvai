package zues.app.law

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import zues.law.CATALOGUE_VERSION
import zues.law.ENGINE_VERSION

/** The version endpoint — no database. Proves it serves the constants the records are stamped with, and nothing else. */
@WebMvcTest(LawController::class)
class LawWebTest {

    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `PM-SYS-010 the catalogue and engine versions in force are served, the ones every computed record is stamped with`() {
        mvc.perform(get("/api/law/version"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.catalogueVersion").value(CATALOGUE_VERSION))
            .andExpect(jsonPath("$.engineVersion").value(ENGINE_VERSION))
            .andExpect(jsonPath("$.length()").value(2))
    }
}
