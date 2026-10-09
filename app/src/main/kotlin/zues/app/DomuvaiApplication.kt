package zues.app

import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.modulith.Modulithic
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.web.SecurityFilterChain

/**
 * The single Spring Boot deployable (ADR-003, ADR-010): a Spring Modulith modular
 * monolith. Each direct sub-package of `zues.app` is an application module with an
 * enforced boundary; `registry` is the first one wired end to end.
 */
@Modulithic(systemName = "domuvai")
@SpringBootApplication(exclude = [UserDetailsServiceAutoConfiguration::class])   // no passwords here: the api issues no credential (ADR-009)
class DomuvaiApplication {

    /**
     * Who is let in (ADR-011). Declared here, on the application class, so that every web slice test runs behind the
     * same rule as the application. Stateless: no session and no cookie, so nothing for a forged form to ride on.
     */
    @Bean
    fun apiSecurity(
        http: HttpSecurity,
        @Value("\${domuvai.auth.issuer-uri:}") issuer: String,
        @Value("\${domuvai.auth.audience:}") audience: String,
        decoder: ObjectProvider<JwtDecoder>,
    ): SecurityFilterChain {
        val auth = ApiAuth(issuer.trim(), audience.trim())
        http.csrf { it.disable() }.sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
        if (!auth.required) return http.authorizeHttpRequests { it.anyRequest().permitAll() }.build()
        return http
            .authorizeHttpRequests { it.requestMatchers("/actuator/health", "/actuator/health/**").permitAll().anyRequest().authenticated() }
            .oauth2ResourceServer { server -> server.jwt { it.decoder(decoder.getIfAvailable { auth.decoder() }) } }
            .build()
    }
}

fun main(args: Array<String>) {
    runApplication<DomuvaiApplication>(*args)
}
