package zues.app.intake

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.time.Clock

/** The clock the guard reads today from, for the intake web tests that bring in the real guard. */
@TestConfiguration
class IntakeTestClock {
    @Bean fun clock(): Clock = Clock.systemUTC()
}
