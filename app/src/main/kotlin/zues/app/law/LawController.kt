package zues.app.law

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import zues.law.CATALOGUE_VERSION
import zues.law.ENGINE_VERSION

/** The versions in force: the rule catalogue's, and the engine's — the pair every computed record is stamped with (ADR-001). */
data class LawVersionView(val catalogueVersion: String, val engineVersion: String)

/**
 * The statutory content's version, served (Rule: PM-SYS-010) — so a screen shows the catalogue in force from the
 * same constants the records are stamped with, and holds no copy that could drift from them.
 */
@RestController
@RequestMapping("/api/law")
class LawController {

    // Rule: PM-SYS-010
    @GetMapping("/version")
    fun version(): LawVersionView = LawVersionView(CATALOGUE_VERSION, ENGINE_VERSION)
}
