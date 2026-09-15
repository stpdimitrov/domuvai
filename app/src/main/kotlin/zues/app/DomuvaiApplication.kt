package zues.app

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.modulith.Modulithic

/**
 * The single Spring Boot deployable (ADR-003, ADR-010): a Spring Modulith modular
 * monolith. Each direct sub-package of `zues.app` is an application module with an
 * enforced boundary; `registry` is the first one wired end to end.
 */
@Modulithic(systemName = "domuvai")
@SpringBootApplication
class DomuvaiApplication

fun main(args: Array<String>) {
    runApplication<DomuvaiApplication>(*args)
}
