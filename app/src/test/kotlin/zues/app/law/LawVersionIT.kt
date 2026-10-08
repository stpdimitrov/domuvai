package zues.app.law

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

/**
 * The schema, after every migration, on a real Postgres: a table that keeps a computed basis keeps the catalogue
 * and engine versions that produced it (PM-SYS-010, ADR-001), and a table that keeps one version keeps the other —
 * an assembly's decision keeps both, though it has no computed basis. Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class LawVersionIT {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(DockerImageName.parse("postgres:16"))

        @DynamicPropertySource
        @JvmStatic
        fun datasource(registry: DynamicPropertyRegistry) {
            val schemas = "registry,identity_org,assembly,money,maintenance,compliance,evidence,app,public"
            registry.add("spring.datasource.url") { "${postgres.jdbcUrl}&currentSchema=$schemas" }
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @Autowired lateinit var jdbc: JdbcTemplate

    /** schema.table → its columns of [names] that are NOT NULL, over every table the migrations made. */
    private fun required(vararg names: String): Map<String, Set<String>> =
        jdbc.queryForList(
            """
            SELECT table_schema || '.' || table_name AS t, column_name AS c FROM information_schema.columns
            WHERE table_schema NOT IN ('pg_catalog', 'information_schema') AND is_nullable = 'NO'
              AND column_name IN (${names.joinToString { "'$it'" }})
            """.trimIndent(),
        ).groupBy({ it["t"] as String }, { it["c"] as String }).mapValues { it.value.toSet() }

    @Test
    fun `PM-SYS-010 every table that keeps a computed basis keeps the catalogue and engine versions that produced it`() {
        val stamped = required("basis_hash", "law_version", "engine_version")      // only tables with at least one of the three
        assertThat(stamped.keys)                                                   // the check sees them, the decisions' table too
            .contains("money.charge_run", "money.payment", "money.fund_handover_statement", "assembly.decision")
        assertThat(stamped.filterValues { !it.containsAll(setOf("law_version", "engine_version")) })
            .`as`("a table with a computed basis, or with one version, that does not require both law_version and engine_version")
            .isEmpty()
        // … and a nullable basis_hash, or a nullable version, is not one that is kept: count them however they are declared
        val declared = jdbc.queryForList(
            """
            SELECT table_schema || '.' || table_name AS t, count(*) AS n FROM information_schema.columns
            WHERE table_schema NOT IN ('pg_catalog', 'information_schema') AND column_name IN ('basis_hash', 'law_version', 'engine_version')
            GROUP BY 1
            """.trimIndent(),
        ).associate { it["t"] as String to (it["n"] as Number).toInt() }
        assertThat(declared).`as`("each of the three columns a table declares is NOT NULL").isEqualTo(stamped.mapValues { it.value.size })
    }
}
