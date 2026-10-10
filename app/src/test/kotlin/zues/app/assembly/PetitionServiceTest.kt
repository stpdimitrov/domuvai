package zues.app.assembly

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import zues.app.registry.HeldOn
import zues.app.registry.Holding
import zues.app.registry.Holdings
import zues.app.registry.Resident
import zues.app.registry.TitleRole
import zues.law.CATALOGUE_VERSION
import zues.law.ENGINE_VERSION
import zues.law.constantOn
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

/**
 * The owners' petition with the repositories mocked and the registry's port faked: the "tables" are lists,
 * and the book is whatever the test says is held on a day.
 */
class PetitionServiceTest {

    private val now = Instant.parse("2026-10-09T21:30:00Z")                  // 00:30 on 10 October in Sofia
    private val today = LocalDate.parse("2026-10-10")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val entranceId = UUID.randomUUID()

    /** The book as the port answers it: titles with the day they begin and, for a sale, the day they end. */
    private data class Titled(val party: UUID, val unit: UUID, val role: TitleRole, val parts: String, val share: String, val from: LocalDate, val to: LocalDate?, val derived: Boolean)
    private val book = mutableListOf<Titled>()
    private val overHeld = mutableSetOf<UUID>()
    private val holdings = object : Holdings {
        override fun inForce(entranceId: UUID, on: LocalDate, role: TitleRole): HeldOn {
            val held = book.filter { it.role == role && it.from <= on && (it.to == null || on < it.to) }
                .map { Holding(it.party, it.unit, role.name, it.share, it.parts, (BigDecimal(it.parts) * BigDecimal(it.share)).stripTrailingZeros().toPlainString(), if (it.derived) "DERIVED" else "DECLARED") }
            return HeldOn(held, if (role == TitleRole.OWN) overHeld.toList() else emptyList())
        }
        override fun residents(entranceId: UUID, on: LocalDate): List<Resident> = emptyList()
    }

    private val petitionsStored = mutableListOf<Petition>()
    private val signed = mutableListOf<PetitionSignature>()
    private val convened = mutableListOf<Assembly>()
    private val aggregates: JdbcAggregateTemplate = mock {
        on { insert(any<Any>()) } doAnswer {
            when (val row = it.arguments[0]) {
                is Petition -> petitionsStored += row
                is PetitionSignature -> signed += row
                is Assembly -> convened += row
            }
            it.arguments[0]
        }
    }
    private val petitions: PetitionRepository = mock {
        on { lock(any(), any()) } doAnswer { call -> petitionsStored.firstOrNull { it.id == call.arguments[0] && it.entranceId == call.arguments[1] } }
        on { findById(any<UUID>()) } doAnswer { call -> Optional.ofNullable(petitionsStored.firstOrNull { it.id == call.arguments[0] }) }
    }
    private val signatures: PetitionSignatureRepository = mock {
        on { findByPetitionIdOrderBySignedAt(any()) } doAnswer { call -> signed.filter { it.petitionId == call.arguments[0] } }
    }
    private val assemblies: AssemblyRepository = mock {
        on { findByPetitionId(any()) } doAnswer { call -> convened.firstOrNull { it.petitionId == call.arguments[0] } }
    }
    private val convening = AssemblyService(aggregates, assemblies, mock(), LawMajorities(), clock)
    private val service = PetitionService(aggregates, petitions, signatures, assemblies, convening, holdings, clock)

    private fun owner(parts: String, role: TitleRole = TitleRole.OWN, unit: UUID = UUID.randomUUID(), from: String = "2026-01-01",
                      to: String? = null, derived: Boolean = false, share: String = "1"): UUID =
        UUID.randomUUID().also { book += Titled(it, unit, role, parts, share, LocalDate.parse(from), to?.let(LocalDate::parse), derived) }

    private fun unitOf(party: UUID) = book.first { it.party == party }.unit

    private fun meeting(by: UUID, demandUnmet: String = "Искането е връчено на управителя на 1 септември; събрание не е свикано.") =
        ConveneOnPetition(by, Instant.parse("2026-11-20T16:00:00Z"), "фоайето на вх. А", "IN_PERSON", demandUnmet)

    private val threshold = constantOn("GA_PETITION_MIN_PCT", "2026-10-10")

    @Test
    fun `PM-GA-003 signatures accumulate until the threshold, then self-convening unlocks`() {
        assertThat(threshold.verified).isFalse()                                     // TODO(legal): PM-GA-003
        assertThat(threshold.value).isEqualTo("20")
        val (a, b, c) = listOf(owner("8.5"), owner("7.25"), owner("4.25"))           // 8.5 → 15.75 → 20 exactly
        owner("80")                                                                  // the rest of the entrance, not signing

        val petition = service.open(entranceId, a, "  Ремонт на покрива  ")
        assertThat(petition.subject).isEqualTo("Ремонт на покрива")
        fun weight() = service.read(entranceId, petition.id).weight
        assertThat(weight().heldPct).isEqualByComparingTo("8.5")                     // who opens it signs it
        assertThat(weight().unlocked).isFalse()
        assertThatThrownBy { service.convene(entranceId, petition.id, meeting(a)) }
            .isInstanceOf(PetitionLocked::class.java).hasMessageContaining("8.5%").hasMessageContaining("20%").hasMessageContaining("чл. 12 ЗУЕС")

        service.sign(entranceId, petition.id, b)
        assertThat(weight().heldPct).isEqualByComparingTo("15.75")
        assertThat(weight().unlocked).isFalse()                                      // 15.75 is not 20

        service.sign(entranceId, petition.id, c)
        val reached = weight()
        assertThat(reached.heldPct).isEqualByComparingTo("20")
        assertThat(reached.thresholdPct).isEqualByComparingTo(threshold.value)
        assertThat(reached.on).isEqualTo(today)                                      // a Sofia day: still the 9th in UTC
        assertThat(reached.unlocked).isTrue()                                        // "at least": exactly the threshold unlocks
        assertThat(service.read(entranceId, petition.id).signatories).containsExactly(a, b, c)

        val assembly = service.convene(entranceId, petition.id, meeting(b))
        assertThat(assembly.status).isEqualTo("DRAFT")
        assertThat(assembly.convenedAs).isEqualTo("OWNERS")
        assertThat(assembly.convenedBy).isEqualTo(b)
        assertThat(assembly.petitionId).isEqualTo(petition.id)
        assertThat(assembly.demandUnmetNote).startsWith("Искането е връчено")
        assertThat(assembly.petitionHeldPct).isEqualByComparingTo("20")
        assertThat(assembly.petitionThresholdPct).isEqualByComparingTo("20")
        assertThat(assembly.petitionWeighedOn).isEqualTo(today)
        assertThat(listOf(assembly.lawVersion, assembly.engineVersion)).containsExactly(CATALOGUE_VERSION, ENGINE_VERSION)
        assertThat(assembly.petitionThresholdConstant).isEqualTo("GA_PETITION_MIN_PCT@${threshold.inForceFrom}")
        assertThat(assembly.petitionThresholdVerified).isFalse()                     // convened on a number counsel had not confirmed
        assertThat(service.read(entranceId, petition.id).assemblyId).isEqualTo(assembly.id)
    }

    @Test
    fun `PM-GA-003 a petition unlocks one assembly, convened by a signatory who says the demand was not met`() {
        val (a, b) = listOf(owner("15"), owner("10"))
        val outsider = owner("30")
        val petition = service.open(entranceId, a, "Ремонт на покрива").also { service.sign(entranceId, it.id, b) }

        assertThatThrownBy { service.convene(entranceId, petition.id, meeting(outsider)) }           // owns more than both, signed nothing
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("signatories")
        for (blank in listOf("", "   ")) {
            assertThatThrownBy { service.convene(entranceId, petition.id, meeting(a, demandUnmet = blank)) }
                .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("not met")
        }
        assertThat(convened).isEmpty()

        service.convene(entranceId, petition.id, meeting(a))
        assertThatThrownBy { service.convene(entranceId, petition.id, meeting(b)) }.isInstanceOf(IllegalStateException::class.java)   // once
        assertThatThrownBy { service.sign(entranceId, petition.id, outsider) }.isInstanceOf(IllegalStateException::class.java)        // and it is closed
        assertThat(convened).hasSize(1)
    }

    @Test
    fun `PM-GA-003 only an owner in the entrance signs, once - a holder of use, a stranger and a second signature are refused`() {
        val a = owner("25")
        val holderOfUse = owner("25", role = TitleRole.USR)
        val petition = service.open(entranceId, a, "Ремонт на покрива")
        assertThatThrownBy { service.sign(entranceId, petition.id, holderOfUse) }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("PM-GA-003")
        assertThatThrownBy { service.sign(entranceId, petition.id, UUID.randomUUID()) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { service.sign(entranceId, petition.id, a) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { service.open(entranceId, holderOfUse, "Друго") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { service.open(entranceId, a, "  ") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(signed.map { it.partyId }).containsExactly(a)
        assertThat(service.read(entranceId, petition.id).weight.heldPct).isEqualByComparingTo("25")   // refused signatures weigh nothing
    }

    @Test
    fun `PM-GA-003 the weight is what the signatories own on the day it is weighed - a signatory who sold no longer counts`() {
        val staying = owner("12")
        val selling = owner("10", to = "2026-10-10")                                 // sells on the 10th: an owner on the 9th, not on the 10th
        val signedOnThe9th = PetitionService(
            aggregates, petitions, signatures, assemblies, convening, holdings, Clock.fixed(Instant.parse("2026-10-09T09:00:00Z"), ZoneOffset.UTC),
        )
        val petition = signedOnThe9th.open(entranceId, staying, "Ремонт на покрива")
        signedOnThe9th.sign(entranceId, petition.id, selling)
        assertThat(signedOnThe9th.read(entranceId, petition.id).weight.unlocked).isTrue()             // 22% on the 9th

        val weight = service.read(entranceId, petition.id).weight                    // read on the 10th
        assertThat(weight.heldPct).isEqualByComparingTo("12")
        assertThat(weight.unlocked).isFalse()
        assertThatThrownBy { service.convene(entranceId, petition.id, meeting(staying)) }.isInstanceOf(PetitionLocked::class.java)
    }

    @Test
    fun `PM-GA-003 a signatory who has sold does not convene, even when the others still hold enough`() {
        val (a, b) = listOf(owner("15"), owner("10"))
        val sold = owner("5", to = "2026-10-10")
        val onThe9th = PetitionService(aggregates, petitions, signatures, assemblies, convening, holdings, Clock.fixed(Instant.parse("2026-10-09T09:00:00Z"), ZoneOffset.UTC))
        val petition = onThe9th.open(entranceId, sold, "Ремонт на покрива")
        onThe9th.sign(entranceId, petition.id, a)
        onThe9th.sign(entranceId, petition.id, b)

        assertThat(service.read(entranceId, petition.id).weight.unlocked).isTrue()   // 25% without the seller
        assertThatThrownBy { service.convene(entranceId, petition.id, meeting(sold)) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("no longer owns")
        assertThat(service.convene(entranceId, petition.id, meeting(a)).convenedBy).isEqualTo(a)
    }

    @Test
    fun `PM-GA-003 a co-owner signs for their share of the unit - not for the unit, and not for the co-owner who did not sign`() {
        val unit = UUID.randomUUID()
        val (half, otherHalf) = listOf(owner("12.5", unit = unit, share = "0.5"), owner("12.5", unit = unit, share = "0.5"))
        val whole = owner("14")
        val petition = service.open(entranceId, half, "Ремонт на покрива")
        fun weight() = service.read(entranceId, petition.id).weight
        assertThat(weight().heldPct).isEqualByComparingTo("6.25")                    // half of 12.5, never 12.5
        service.sign(entranceId, petition.id, whole)
        assertThat(weight().heldPct).isEqualByComparingTo("20.25")
        assertThat(weight().unlocked).isTrue()
        service.sign(entranceId, petition.id, otherHalf)
        assertThat(weight().heldPct).isEqualByComparingTo("26.5")                    // the two halves together are the unit, once
    }

    @Test
    fun `PM-GA-003 a unit with both an owner and a holder of use is not weighed - the petition says why and does not unlock`() {
        val plain = owner("30")
        val shared = owner("25")
        val petition = service.open(entranceId, plain, "Ремонт на покрива")
        service.sign(entranceId, petition.id, shared)
        assertThat(service.read(entranceId, petition.id).weight.heldPct).isEqualByComparingTo("55")
        assertThat(service.read(entranceId, petition.id).weight.unlocked).isTrue()   // owners only: weighed in full

        owner("25", role = TitleRole.USR, unit = unitOf(shared))                     // a right of use over a signatory's unit
        val weight = service.read(entranceId, petition.id).weight
        assertThat(weight.heldPct).isEqualByComparingTo("30")                        // the unit is left out of the figure; past the threshold, and still
        assertThat(weight.unlocked).isFalse()
        assertThat(weight.cannotWeigh).singleElement().asString()
            .contains(unitOf(shared).toString()).contains("holder of a right of use").contains("TODO(legal): PM-GA-003")
        assertThatThrownBy { service.convene(entranceId, petition.id, meeting(plain)) }
            .isInstanceOf(PetitionLocked::class.java).hasMessageContaining("cannot be weighed").hasMessageContaining(unitOf(shared).toString())
        assertThat(convened).isEmpty()
    }

    @Test
    fun `PM-GA-003 a petition in an entrance with such a unit cannot be weighed even when no signatory owns it, until the right of use ends`() {
        val signing = owner("25")
        val other = owner("25")
        owner("25", role = TitleRole.USR, unit = unitOf(other), to = "2026-10-11")   // a right of use that ends tomorrow
        val later = owner("10", role = TitleRole.USR, unit = unitOf(signing), from = "2026-12-01")   // and one that has not begun
        val petition = service.open(entranceId, signing, "Ремонт на покрива")
        val weight = service.read(entranceId, petition.id).weight
        assertThat(weight.heldPct).isEqualByComparingTo("25")                        // the signatory's own unit is plain today
        assertThat(weight.cannotWeigh).singleElement().asString().contains(unitOf(other).toString())
        assertThat(weight.unlocked).isFalse()

        val tomorrow = PetitionService(aggregates, petitions, signatures, assemblies, convening, holdings, Clock.fixed(now.plusSeconds(86_400), ZoneOffset.UTC))
        assertThat(tomorrow.read(entranceId, petition.id).weight.cannotWeigh).isEmpty()              // owners only again: weighed in full
        assertThat(tomorrow.read(entranceId, petition.id).weight.unlocked).isTrue()
        assertThat(later).isNotNull()
    }

    @Test
    fun `PM-GA-003 a signatory's unit owned for more than the whole is not weighed, and a derived weight is flagged`() {
        val doubled = owner("30")
        overHeld += unitOf(doubled)
        val petition = service.open(entranceId, doubled, "Ремонт на покрива")
        val weight = service.read(entranceId, petition.id).weight
        assertThat(weight.unlocked).isFalse()
        assertThat(weight.cannotWeigh).singleElement().asString().contains("PM-ORG-005")
        assertThat(weight.derivedParts).isFalse()

        overHeld.clear()
        val fromArea = owner("5", derived = true)
        service.sign(entranceId, petition.id, fromArea)
        val warned = service.read(entranceId, petition.id).weight
        assertThat(warned.unlocked).isTrue()
        assertThat(warned.derivedParts).isTrue()
    }

    @Test
    fun `PM-GA-002 the owners' capacity cannot be claimed through the ordinary convene, and a petition of another entrance is not found`() {
        val a = owner("25")
        assertThatThrownBy { convening.convene(entranceId, Convene(a, "OWNERS", Instant.parse("2026-11-20T16:00:00Z"), "фоайето", "IN_PERSON")) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("PM-GA-002")
        val petition = service.open(entranceId, a, "Ремонт на покрива")
        val elsewhere = UUID.randomUUID()
        assertThatThrownBy { service.read(elsewhere, petition.id) }.isInstanceOf(NoSuchElementException::class.java)
        assertThatThrownBy { service.sign(elsewhere, petition.id, a) }.isInstanceOf(NoSuchElementException::class.java)
        assertThatThrownBy { service.convene(elsewhere, petition.id, meeting(a)) }.isInstanceOf(NoSuchElementException::class.java)
    }
}
