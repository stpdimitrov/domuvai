package zues.app.money

import org.postgresql.util.PGobject
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.convert.WritingConverter
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration

/** A jsonb payload carried as its serialized text. Spring Data JDBC does not map `jsonb`
 *  out of the box; the converters below bridge it to a PostgreSQL `PGobject`. */
data class JsonbValue(val json: String)

@WritingConverter
class JsonbWritingConverter : Converter<JsonbValue, PGobject> {
    override fun convert(source: JsonbValue): PGobject =
        PGobject().apply { type = "jsonb"; value = source.json }
}

@ReadingConverter
class JsonbReadingConverter : Converter<PGobject, JsonbValue> {
    override fun convert(source: PGobject): JsonbValue = JsonbValue(source.value ?: "{}")
}

/**
 * Registers the jsonb converters. Extending AbstractJdbcConfiguration (Spring Boot backs off
 * its own when one is present) keeps the dialect's default conversions and adds these.
 */
@Configuration
class JdbcConfig : AbstractJdbcConfiguration() {
    override fun userConverters(): List<Any> = listOf(JsonbWritingConverter(), JsonbReadingConverter())
}
