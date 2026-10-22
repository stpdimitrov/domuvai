package zues.app.evidence

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataAccessException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.util.UUID

/**
 * Documents against real PostgreSQL: what is stored reads back byte for byte, a re-upload is a version beside the
 * old one, and the tables themselves refuse to change, remove or mistype what they hold. Docker-gated.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class DocumentsPersistenceIT {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(DockerImageName.parse("postgres:16"))

        @DynamicPropertySource
        @JvmStatic
        fun datasource(registry: DynamicPropertyRegistry) {
            val schemas = "registry,identity_org,assembly,money,maintenance,compliance,evidence,app,intake,public"
            registry.add("spring.datasource.url") { "${postgres.jdbcUrl}&currentSchema=$schemas" }
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var documents: Documents

    private fun created(path: String, body: String, field: String) = UUID.fromString(
        json.readTree(
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated).andReturn().response.contentAsString,
        ).get(field).asText(),
    )

    private fun entrance() = created("/api/registry/entrances", """{"address":"ул. Раковски 1","label":"А","managementForm":"GA"}""", "entranceId")
    private fun party() = created("/api/registry/parties", """{"fullName":"Мария Иванова"}""", "partyId")

    private val paper = PaperOriginal("архивът в стаята на УС", "председателят на УС")

    private fun protocol(entranceId: UUID, author: UUID, entity: String, bytes: ByteArray, supersedes: UUID? = null) =
        documents.store(StoreDocument(entranceId, entity, "PROTOCOL", "application/pdf", bytes, author, paper, supersedes))

    @Test
    fun `PM-DOC-001 a document is stored with its content hash, author, time and entity, and reads back byte for byte`() {
        val entranceId = entrance()
        val author = party()
        val entity = "assembly:${UUID.randomUUID()}"
        val bytes = "протокол от общото събрание".toByteArray()

        val stored = protocol(entranceId, author, entity, bytes)

        val read = documents.find(entranceId, stored.id)!!
        assertThat(read).isEqualTo(stored)
        assertThat(read.entityRef).isEqualTo(entity)
        assertThat(read.authorPartyId).isEqualTo(author)
        assertThat(read.contentHash).isEqualTo(jdbc.queryForObject("SELECT encode(sha256(?), 'hex')", String::class.java, bytes))
        assertThat(read.paperOriginal).isEqualTo(paper)
        assertThat(documents.content(entranceId, stored.id)).isEqualTo(bytes)
        // another entrance does not have it
        assertThat(documents.find(entrance(), stored.id)).isNull()
        assertThat(documents.content(entrance(), stored.id)).isNull()
    }

    @Test
    fun `PM-DOC-001 re-upload creates a new version - nothing is overwritten`() {
        val entranceId = entrance()
        val author = party()
        val entity = "assembly:${UUID.randomUUID()}"
        val first = protocol(entranceId, author, entity, "първи".toByteArray())

        val second = protocol(entranceId, author, entity, "втори".toByteArray(), supersedes = first.id)

        assertThat(second.id).isNotEqualTo(first.id)
        assertThat(second.versionNo).isEqualTo(2)
        assertThat(second.documentKey).isEqualTo(first.id)
        assertThat(documents.find(entranceId, first.id)).isEqualTo(first)
        assertThat(documents.content(entranceId, first.id)).isEqualTo("първи".toByteArray())
        assertThat(documents.content(entranceId, second.id)).isEqualTo("втори".toByteArray())
        assertThat(documents.versions(entranceId, first.id).map { it.id }).containsExactly(first.id, second.id)
        assertThat(documents.versions(entranceId, second.id).map { it.id }).containsExactly(first.id, second.id)

        // the same bytes again: a version of their own, the bytes kept once
        val third = protocol(entranceId, author, entity, "първи".toByteArray(), supersedes = second.id)
        assertThat(third.contentHash).isEqualTo(first.contentHash)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM evidence.blob WHERE content_hash = ?", Int::class.java, first.contentHash)).isEqualTo(1)

        // versions form a line: a version already replaced is not replaced again, nor by another document's
        assertThatThrownBy { protocol(entranceId, author, entity, "четвърти".toByteArray(), supersedes = first.id) }
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("PM-DOC-001")
        assertThatThrownBy { protocol(entranceId, author, "assembly:${UUID.randomUUID()}", "друг".toByteArray(), supersedes = third.id) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("PM-DOC-001")
        assertThatThrownBy {
            documents.store(StoreDocument(entranceId, entity, "NOTICE", "application/pdf", "покана".toByteArray(), author, supersedes = third.id))
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("PM-DOC-001")
        assertThatThrownBy { protocol(entrance(), author, entity, "чужд".toByteArray(), supersedes = third.id) }
            .isInstanceOf(NoSuchElementException::class.java)
        assertThat(documents.versions(entranceId, first.id).map { it.versionNo }).containsExactly(1, 2, 3)
    }

    @Test
    fun `PM-DOC-001 the tables refuse to change, remove or empty a document, or to keep bytes under a hash not theirs`() {
        val entranceId = entrance()
        val stored = protocol(entranceId, party(), "assembly:${UUID.randomUUID()}", "оригинал".toByteArray())

        // a document row: an update and a delete do nothing
        jdbc.update("UPDATE evidence.document SET entity_ref = 'assembly:00000000-0000-0000-0000-000000000000' WHERE id = ?", stored.id)
        jdbc.update("DELETE FROM evidence.document WHERE id = ?", stored.id)
        assertThat(documents.find(entranceId, stored.id)).isEqualTo(stored)
        // the bytes: refused outright
        assertThatThrownBy { jdbc.update("UPDATE evidence.blob SET bytes = ? WHERE content_hash = ?", "подмяна".toByteArray(), stored.contentHash) }
            .isInstanceOf(DataAccessException::class.java).hasMessageContaining("neither changed nor removed")
        assertThatThrownBy { jdbc.update("DELETE FROM evidence.blob WHERE content_hash = ?", stored.contentHash) }.isInstanceOf(DataAccessException::class.java).hasMessageContaining("neither changed nor removed")
        assertThatThrownBy { jdbc.execute("TRUNCATE evidence.blob CASCADE") }.isInstanceOf(DataAccessException::class.java).hasMessageContaining("neither changed nor removed")
        assertThatThrownBy { jdbc.execute("TRUNCATE evidence.document") }.isInstanceOf(DataAccessException::class.java).hasMessageContaining("neither changed nor removed")
        assertThat(documents.content(entranceId, stored.id)).isEqualTo("оригинал".toByteArray())
        // bytes under a hash that is not theirs
        assertThatThrownBy { jdbc.update("INSERT INTO evidence.blob (content_hash, bytes) VALUES (?, ?)", "0".repeat(64), "нещо".toByteArray()) }
            .isInstanceOf(DataAccessException::class.java).hasMessageContaining("blob_is_its_hash")
    }

    /** A row written past the service, so the table's own constraints are what is tested. Returns its id. */
    private fun insert(
        entranceId: UUID, author: UUID?, kind: String, location: String?, custodian: String?,
        entity: String = "assembly:${UUID.randomUUID()}", mediaType: String = "application/pdf",
        id: UUID = UUID.randomUUID(), key: UUID = id, versionNo: Int = 1, supersedes: UUID? = null,
    ): UUID {
        val bytes = UUID.randomUUID().toString().toByteArray()
        val hash = jdbc.queryForObject("SELECT encode(sha256(?), 'hex')", String::class.java, bytes)
        jdbc.update("INSERT INTO evidence.blob (content_hash, bytes) VALUES (?, ?)", hash, bytes)
        jdbc.update(
            "INSERT INTO evidence.document (id, entrance_id, entity_ref, kind, content_hash, media_type, author_party_id, document_key, version_no, supersedes, paper_location, paper_custodian) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            id, entranceId, entity, kind, hash, mediaType, author, key, versionNo, supersedes, location, custodian,
        )
        return id
    }

    private fun refused(constraint: String, write: () -> Unit) {
        assertThatThrownBy { write() }.isInstanceOf(DataAccessException::class.java).hasMessageContaining(constraint)
    }

    @Test
    fun `PM-DOC-001 the table keeps versions in a line of one document - written past the service, it still refuses a fork, a stray or a nameless row`() {
        val entranceId = entrance()
        val author = party()
        val entity = "assembly:${UUID.randomUUID()}"
        val first = insert(entranceId, author, "NOTICE", null, null, entity)
        val second = insert(entranceId, author, "NOTICE", null, null, entity, key = first, versionNo = 2, supersedes = first)
        refused("document_one_successor") { insert(entranceId, author, "NOTICE", null, null, entity, key = first, versionNo = 3, supersedes = first) }
        refused("document_one_version_no") { insert(entranceId, author, "NOTICE", null, null, entity, key = first, versionNo = 2, supersedes = second) }
        refused("document_supersedes_its_own") { insert(entranceId, author, "NOTICE", null, null, "assembly:${UUID.randomUUID()}", key = first, versionNo = 3, supersedes = second) }
        refused("document_supersedes_its_own") { insert(entranceId, author, "FILING", null, null, entity, key = first, versionNo = 3, supersedes = second) }
        refused("document_first_version") { insert(entranceId, author, "NOTICE", null, null, entity, key = UUID.randomUUID()) }
        refused("document_first_version") { insert(entranceId, author, "NOTICE", null, null, entity, versionNo = 2) }
        refused("document_names_its_entity") { insert(entranceId, author, "NOTICE", null, null, entity = "събрание") }
        refused("media_type") { insert(entranceId, author, "NOTICE", null, null, mediaType = "PDF") }
        refused("author_party_id") { insert(entranceId, null, "NOTICE", null, null) }
    }

    @Test
    fun `PM-DOC-002 the table refuses a document of no statutory kind`() {
        val entranceId = entrance()
        val author = party()
        insert(entranceId, author, "NOTICE", null, null)
        for (kind in listOf("OTHER", "", "notice")) {
            refused("document_kind_is_statutory") { insert(entranceId, author, kind, null, null) }
        }
    }

    @Test
    fun `PM-DOC-007 the table refuses a protocol with no archive location or custodian`() {
        val entranceId = entrance()
        val author = party()
        insert(entranceId, author, "PROTOCOL", "архивът", "председателят на УС")
        refused("document_protocol_paper") { insert(entranceId, author, "PROTOCOL", null, null) }
        refused("document_paper_whole") { insert(entranceId, author, "PROTOCOL", "архивът", null) }
        refused("document_paper_said") { insert(entranceId, author, "PROTOCOL", " ", "председателят на УС") }
    }
}
