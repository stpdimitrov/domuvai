package zues.app

import org.junit.jupiter.api.Test
import org.springdoc.core.configuration.SpringDocConfiguration
import org.springdoc.core.configuration.SpringDocJacksonKotlinModuleConfiguration
import org.springdoc.core.configuration.SpringDocKotlinConfiguration
import org.springdoc.core.configuration.SpringDocSpecPropertiesConfiguration
import org.springdoc.core.properties.SpringDocConfigProperties
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import zues.app.intake.ImportService
import zues.app.money.ArrearsService
import zues.app.money.ChargeRunService
import zues.app.money.ChargeRunStore
import zues.app.money.FundAccountService
import zues.app.money.StatementService
import zues.app.registry.BookService
import zues.app.registry.OwnershipService
import zues.app.registry.RegistryService
import java.io.File
import java.time.Clock

/**
 * The published HTTP contract is generated from the running code (ADR-013).
 *
 * Boots the web layer only — every controller, its collaborators mocked — so no database and no
 * Docker are needed. springdoc reads the controllers and their Kotlin types and the raw spec goes to
 * `build/openapi/api-docs.json`; `tools/build_openapi.py` (gate 6/9) merges each operation's rule
 * citations and publishes `docs/api/openapi.json`. A new controller collaborator is mocked here.
 */
@WebMvcTest(
    properties = [
        "springdoc.api-docs.version=openapi_3_1",
        "springdoc.default-produces-media-type=application/json",
    ],
)
@ImportAutoConfiguration(
    SpringDocConfiguration::class, SpringDocConfigProperties::class, SpringDocSpecPropertiesConfiguration::class,
    SpringDocWebMvcConfiguration::class, SpringDocKotlinConfiguration::class,
    SpringDocJacksonKotlinModuleConfiguration::class,
)
class OpenApiContractTest {

    @Autowired lateinit var mvc: MockMvc

    @MockitoBean lateinit var imports: ImportService
    @MockitoBean lateinit var arrears: ArrearsService
    @MockitoBean lateinit var chargeRuns: ChargeRunService
    @MockitoBean lateinit var chargeRunStore: ChargeRunStore
    @MockitoBean lateinit var fund: FundAccountService
    @MockitoBean lateinit var statements: StatementService
    @MockitoBean lateinit var book: BookService
    @MockitoBean lateinit var ownership: OwnershipService
    @MockitoBean lateinit var registry: RegistryService

    @TestConfiguration
    class SystemClock {
        @Bean fun clock(): Clock = Clock.systemUTC()
    }

    @Test
    fun `the published contract is generated from the controllers`() {
        val spec = mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        File("build/openapi/api-docs.json").apply { parentFile.mkdirs() }.writeText(spec)
    }
}
