package zues.app.assembly

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import zues.app.identity_org.Logins
import zues.app.identity_org.WhoAsks
import zues.app.policy.Policy
import java.time.Clock
import java.util.UUID

/**
 * With sign-in switched off (`DOMUVAI_AUTH=off`, as the test task runs) nobody is asking, and a record is made with
 * nobody named: the demo seed reaches it through the api so. Unlike the acts of a sitting, it has no party to name.
 */
@WebMvcTest(PaperDecisionController::class, properties = ["domuvai.auth.mode=off"])
@Import(WhoAsks::class, PaperDecisions::class, AssemblyReads::class, Policy::class, PaperDecisionSignInOffWebTest.Fixed::class)
class PaperDecisionSignInOffWebTest {

    class Fixed {
        @Bean fun clock(): Clock = Clock.systemUTC()
    }

    @Autowired lateinit var mvc: MockMvc
    @MockitoBean lateinit var aggregates: JdbcAggregateTemplate
    @MockitoBean lateinit var stored: PaperDecisionRepository
    @MockitoBean lateinit var logins: Logins

    @Test
    fun `PM-FEE-012 with sign-in switched off a decision taken on paper is recorded, naming nobody`() {
        whenever(aggregates.insert(any<PaperDecision>())).thenAnswer { it.arguments[0] }
        val content = """{"decisionText":"вноска","protocolDate":"2026-03-14","protocolRef":"Протокол № 3","archiveLocation":"шкаф","custodian":"Иван Иванов"}"""
        mvc.perform(post("/api/assembly/entrances/${UUID.randomUUID()}/paper-decisions").contentType(MediaType.APPLICATION_JSON).content(content))
            .andExpect(status().isCreated).andExpect(jsonPath("$.id").isNotEmpty)
        val row = argumentCaptor<PaperDecision>().apply { verify(aggregates).insert(capture()) }.firstValue
        assertThat(row.loginIssuer).isNull()
        assertThat(row.loginSubject).isNull()
    }
}
