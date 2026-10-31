package zues.app.registry

import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.util.UUID

/**
 * The migration of ABS-01 against rows already stored: the schema is brought to the version before
 * it, an absence is stored there, and the migration fills the new column. Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
class AbsenceHandedInMigrationIT {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(DockerImageName.parse("postgres:16"))
    }

    private val schemas = "registry,identity_org,assembly,money,maintenance,compliance,evidence,app,intake,public"

    private fun flywayTo(version: String) = Flyway.configure()
        .dataSource("${postgres.jdbcUrl}&currentSchema=$schemas", postgres.username, postgres.password)
        .defaultSchema("public").locations("classpath:db/migration").target(version).load()

    @Test
    fun `PM-FEE-007 an absence already stored takes the day it was entered as the day it was handed in`() {
        val before = flywayTo("202610311350").also { it.migrate() }
        val jdbc = JdbcTemplate(before.configuration.dataSource)
        val (condominium, entrance, unit, absence) = List(4) { UUID.randomUUID() }
        jdbc.update("INSERT INTO registry.condominium (id, address) VALUES (?, 'ул. Раковски 1')", condominium)
        jdbc.update("INSERT INTO registry.entrance (id, condominium_id, label, management_form) VALUES (?, ?, 'А', 'GA')", entrance, condominium)
        jdbc.update("INSERT INTO registry.unit (id, entrance_id, designation, unit_type, ideal_parts_pct) VALUES (?, ?, 'ап. 1', 'FLAT', 100)", unit, entrance)
        jdbc.update(
            "INSERT INTO registry.absence_declaration (id, entrance_id, unit_id, absent_from, absent_to, filed_on) " +
                "VALUES (?, ?, ?, DATE '2026-02-01', DATE '2026-03-01', DATE '2026-03-10')",
            absence, entrance, unit,
        )

        flywayTo("latest").migrate()

        val stored = jdbc.queryForObject("SELECT handed_in_on FROM registry.absence_declaration WHERE id = ?", String::class.java, absence)
        assertThat(stored).isEqualTo("2026-03-10")
    }
}
