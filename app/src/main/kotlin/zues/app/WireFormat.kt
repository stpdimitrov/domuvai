package zues.app

import com.fasterxml.jackson.annotation.JsonInclude
import org.springframework.context.annotation.Configuration
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * The wire says what the published contract says (ADR-013, E2E-01): a response leaves an empty property out
 * instead of sending `null` — the client generated from the contract types it as absent. Scoped to HTTP responses on
 * purpose: the shared ObjectMapper also writes the event publication registry, whose stored text must not change, so
 * only the converter's own copy changes. A map keeps its null values; only a property is left out.
 */
@Configuration
class WireFormat : WebMvcConfigurer {
    override fun extendMessageConverters(converters: MutableList<HttpMessageConverter<*>>) {
        converters.filterIsInstance<MappingJackson2HttpMessageConverter>().forEach {
            it.objectMapper = it.objectMapper.copy()
                .setDefaultPropertyInclusion(JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.ALWAYS))
        }
    }
}
