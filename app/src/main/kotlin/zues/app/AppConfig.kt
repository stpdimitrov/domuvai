package zues.app

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * App-wide infrastructure beans. Time is injected, never read from a static call, so a
 * test can pin it and legal-date logic (PM-SYS-002) never depends on the wall clock.
 */
@Configuration
class AppConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
