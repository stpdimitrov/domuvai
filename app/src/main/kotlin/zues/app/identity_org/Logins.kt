package zues.app.identity_org

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.SQLException
import java.util.UUID

/** The login or the party is already tied: a tie is made once and never silently moved. */
class LoginAlreadyTied(message: String) : RuntimeException(message)

/**
 * A login is a party (ADR-011): the tie between a sign-in — the issuer and the subject of its token — and one
 * registered party. The api keeps the tie itself; a claim in the token would let the provider say who a person is in
 * the book. Who may tie a login, and the endpoint for it, come with the policy module (ADR-002) — until then any
 * signed-in person could otherwise declare themselves anyone.
 */
@Service
class Logins(private val jdbc: JdbcClient) {

    /** The party this login is tied to, or null when it is tied to none. The same subject under another issuer is another login. */
    @Transactional(readOnly = true)
    fun partyOf(issuer: String, subject: String): UUID? =
        jdbc.sql("SELECT party_id FROM identity_org.login WHERE issuer = ? AND subject = ?")
            .params(issuer, subject).query(UUID::class.java).optional().orElse(null)

    /**
     * Ties a login to a registered party, once: neither the login nor, under this issuer, the party may be tied already.
     * A refusal for being tied already leaves the caller's transaction usable; one for an unregistered party does not.
     */
    @Transactional
    fun tie(issuer: String, subject: String, partyId: UUID) {
        require(issuer.isNotBlank() && subject.isNotBlank()) { "a login is an issuer and a subject: neither may be blank" }
        val tied = try {
            jdbc.sql("INSERT INTO identity_org.login (issuer, subject, party_id) VALUES (?, ?, ?) ON CONFLICT DO NOTHING")
                .params(issuer, subject, partyId).update()
        } catch (e: DataIntegrityViolationException) {
            // Only the reference to the party is answered here; anything else the database refused is not ours to explain.
            if ((e.rootCause as? SQLException)?.sqlState != FOREIGN_KEY_VIOLATION) throw e
            throw IllegalArgumentException("no party $partyId is registered")
        }
        if (tied == 0) throw LoginAlreadyTied("this login is already tied to a party, or the party to another login of this issuer")
    }

    private companion object {
        const val FOREIGN_KEY_VIOLATION = "23503"
    }
}
