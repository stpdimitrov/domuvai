package zues.app

import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

/**
 * The boundary check (ADR-003): every direct sub-package of `zues.app` is a module, and
 * `verify()` fails the build on a disallowed dependency between them. Pure classpath
 * analysis — no Spring context, no database — so it runs everywhere the gate pack runs.
 */
class ModularityTests {

    private val modules = ApplicationModules.of(DomuvaiApplication::class.java)

    @Test
    fun `the module structure is valid`() {
        modules.verify()
    }
}
