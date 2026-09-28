package zues.app.registry

import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.jdbc.core.JdbcAggregateTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import zues.kernel.IdealParts
import zues.kernel.toSofiaDate
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * What the caller asks for. An entrance founds a new building at [address], or joins the existing
 * building [condominiumId] — one or the other (Rule: PM-ORG-001). Validation of the enum lives at
 * the edge (DB CHECK is the backstop).
 */
data class RegisterEntrance(
    val address: String?,
    val label: String,
    val managementForm: String,
    val condominiumId: UUID? = null,
)

/** What the caller gets back — the ids the two inserts produced. */
data class EntranceCreated(
    val entranceId: UUID,
    val condominiumId: UUID,
)

/** One unit to register under an entrance. Ideal parts is an exact decimal percent string. */
data class RegisterUnit(
    val designation: String,
    val unitType: String,
    val areaM2: BigDecimal? = null,
    val idealParts: String,
    val separateEntrance: Boolean = false,
)

/**
 * One unit as a committed import delivers it (S-41b): the unit, and what the sheet said about its
 * household and owner. [occupants] are the persons the firm charged (PM-FEE-008); [childrenUnder6]
 * live there on top and are never charged (PM-FEE-005); [ownerName] is a name only (PM-BOOK-011).
 */
data class ImportedUnit(
    val unit: RegisterUnit,
    val occupants: Int = 0,
    val childrenUnder6: Int = 0,
    val ownerName: String? = null,
)

/** One resident to register in a unit's household. `validFrom` defaults to today in Sofia (PM-SYS-004). */
data class RegisterMember(
    val isChildUnder6: Boolean = false,
    val validFrom: String? = null,
)

/** One animal to register in a unit. `validFrom` defaults to today in Sofia (PM-SYS-004). */
data class RegisterAnimal(
    val species: String,
    val vetPassportNo: String? = null,
    val validFrom: String? = null,
)

/**
 * One filed absence to record for a unit — a closed span `[absentFrom, absentTo)` of non-use
 * (Rule: PM-FEE-006/007). Both bounds are required; the filing date is stamped by the system.
 */
data class RegisterAbsence(
    val absentFrom: String,
    val absentTo: String,
)

/**
 * The registry module's one public operation for the walking skeleton: create a
 * condominium and its first entrance, then raise [EntranceRegistered]. The insert and the
 * event share one transaction, so the outbox row cannot outlive a rolled-back write.
 */
@Service
class RegistryService(
    private val aggregates: JdbcAggregateTemplate,
    private val entrances: EntranceRepository,
    private val units: PropertyUnitRepository,
    private val household: HouseholdMemberRepository,
    private val titles: TitleRepository,
    private val parties: PartyRepository,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    @Transactional
    fun registerEntrance(command: RegisterEntrance): EntranceCreated {
        // Rule: PM-ORG-001 — a building's entrances may each run their own assembly, manager and
        // accounts, so a second entrance joins its building instead of founding another; each
        // entrance stays its own isolation unit (ADR-005) with its own 100% of ideal parts.
        require((command.address == null) != (command.condominiumId == null)) {
            "give a new building's address or an existing building's condominiumId — one of the two"
        }
        val condominiumId = command.condominiumId
            ?.also { if (!aggregates.existsById(it, Condominium::class.java)) throw NoSuchElementException("no building $it") }
            ?: aggregates.insert(Condominium(UUID.randomUUID(), command.address!!)).id
        val entrance = aggregates.insert(
            Entrance(UUID.randomUUID(), condominiumId, command.label, command.managementForm),
        )
        events.publishEvent(EntranceRegistered(entrance.id, condominiumId, clock.instant()))
        return EntranceCreated(entrance.id, condominiumId)
    }

    @Transactional(readOnly = true)
    fun listEntrances(): List<Entrance> = entrances.findAll()

    /**
     * Register the complete set of units for an entrance. Rule: PM-ORG-002 — their ideal
     * parts must sum to 100%, checked here before the writes and again by the deferred DB
     * trigger at commit. The whole set is inserted in one transaction so a mid-set state
     * that does not yet sum to 100% never has to be valid.
     */
    @Transactional
    fun registerUnits(entranceId: UUID, commands: List<RegisterUnit>): List<UUID> {
        if (!entrances.existsById(entranceId)) {
            throw NoSuchElementException("no entrance $entranceId")
        }
        UnitValidation.requirePartsSumTo100(commands.map { it.idealParts })
        return commands.map { command ->
            aggregates.insert(
                PropertyUnit(
                    id = UUID.randomUUID(),
                    entranceId = entranceId,
                    designation = command.designation,
                    unitType = command.unitType,
                    areaM2 = command.areaM2,
                    idealPartsPct = UnitValidation.toColumn(IdealParts.of(command.idealParts)),
                    separateEntrance = command.separateEntrance,
                ),
            ).id
        }
    }

    @Transactional(readOnly = true)
    fun listUnits(entranceId: UUID): List<PropertyUnit> = units.findByEntranceId(entranceId)

    /**
     * Adopt the units of a committed fee-sheet import into the book, each stamped with its
     * [importId] so the whole import is revertible as a unit (STAGE1-ADDENDUM §1, step 6). The
     * same PM-ORG-002 invariant registerUnits enforces applies: the set must sum to 100%, checked
     * here and again by the deferred DB trigger at commit. Idempotent on [importId] — delivery of
     * ImportCommitted is at least once, so a redelivery adopts nothing twice. Called by the
     * registry's own event listener, never by intake directly (ADR-003; MODULE-TEMPLATE law 3).
     *
     * Each unit's household and owner come with it (S-41b), valid from [effectiveFrom] — the
     * import's legal date (PM-ORG-011) — and stamped with [importId] like the unit: household
     * members for the persons charged and the children on top (PM-BOOK-002), and a name-only party
     * holding an OWN title, share 1. Rows are never merged across units: two people share names.
     */
    @Transactional
    fun adoptImport(entranceId: UUID, importId: UUID, effectiveFrom: LocalDate, imported: List<ImportedUnit>): List<UUID> {
        if (!entrances.existsById(entranceId)) throw NoSuchElementException("no entrance $entranceId")
        if (units.findByImportId(importId).isNotEmpty()) return emptyList()   // already adopted
        UnitValidation.requirePartsSumTo100(imported.map { it.unit.idealParts })
        return imported.map { row ->
            val command = row.unit
            val unitId = aggregates.insert(
                PropertyUnit(
                    id = UUID.randomUUID(),
                    entranceId = entranceId,
                    designation = command.designation,
                    unitType = command.unitType,
                    areaM2 = command.areaM2,
                    idealPartsPct = UnitValidation.toColumn(IdealParts.of(command.idealParts)),
                    separateEntrance = command.separateEntrance,
                    importId = importId,
                ),
            ).id
            val members = List(row.occupants) { false } + List(row.childrenUnder6) { true }
            members.forEach { child ->
                aggregates.insert(
                    HouseholdMember(
                        id = UUID.randomUUID(), entranceId = entranceId, unitId = unitId, partyId = null,
                        isChildUnder6 = child, validFrom = effectiveFrom, validTo = null, importId = importId,
                    ),
                )
            }
            row.ownerName?.let { name ->
                val partyId = aggregates.insert(Party(UUID.randomUUID(), name, idType = null, idValue = null, importId = importId)).id
                aggregates.insert(
                    Title(
                        id = UUID.randomUUID(), entranceId = entranceId, unitId = unitId, partyId = partyId,
                        titleRole = TitleRole.OWN.name, share = BigDecimal.ONE, validFrom = effectiveFrom, validTo = null,
                        importId = importId,
                    ),
                )
            }
            unitId
        }
    }

    /**
     * Undo an import: drop every row that carried its [importId] (STAGE1-ADDENDUM §1) — the rows
     * that point at a unit first, then the units. A record added later and pointing at an imported
     * unit is not the import's to drop: the unit's delete then fails and nothing is removed.
     */
    @Transactional
    fun revertImport(importId: UUID) {
        household.deleteAll(household.findByImportId(importId))
        titles.deleteAll(titles.findByImportId(importId))
        parties.deleteAll(parties.findByImportId(importId))
        units.deleteAll(units.findByImportId(importId))
    }

    /**
     * Register residents in a unit's household — the headcount a per-person charge builds on
     * (Rule: PM-FEE-008). Children under six are flagged for separate treatment (Rule:
     * PM-FEE-005). The unit must exist and belong to the entrance.
     */
    @Transactional
    fun registerHousehold(entranceId: UUID, unitId: UUID, members: List<RegisterMember>): List<UUID> {
        val unit = units.findById(unitId).orElseThrow { NoSuchElementException("no unit $unitId") }
        if (unit.entranceId != entranceId) {
            throw NoSuchElementException("unit $unitId is not in entrance $entranceId")
        }
        val today = LocalDate.parse(toSofiaDate(clock.instant()))               // a Sofia calendar day (PM-SYS-004)
        return members.map { member ->
            aggregates.insert(
                HouseholdMember(
                    id = UUID.randomUUID(),
                    entranceId = entranceId,
                    unitId = unitId,
                    partyId = null,
                    isChildUnder6 = member.isChildUnder6,
                    validFrom = member.validFrom?.let { LocalDate.parse(it) } ?: today,
                    validTo = null,
                ),
            ).id
        }
    }

    /**
     * Record animals kept in a unit — a separate section of the book with veterinary passport
     * data (Rule: PM-BOOK-005). Each becomes an occupant-equivalent in a per-person charge
     * (Rule: PM-FEE-009). The unit must exist and belong to the entrance.
     */
    @Transactional
    fun registerAnimals(entranceId: UUID, unitId: UUID, animals: List<RegisterAnimal>): List<UUID> {
        val unit = units.findById(unitId).orElseThrow { NoSuchElementException("no unit $unitId") }
        if (unit.entranceId != entranceId) {
            throw NoSuchElementException("unit $unitId is not in entrance $entranceId")
        }
        val today = LocalDate.parse(toSofiaDate(clock.instant()))               // a Sofia calendar day (PM-SYS-004)
        return animals.map { animal ->
            aggregates.insert(
                Animal(
                    id = UUID.randomUUID(),
                    entranceId = entranceId,
                    unitId = unitId,
                    species = animal.species,
                    vetPassportNo = animal.vetPassportNo,
                    validFrom = animal.validFrom?.let { LocalDate.parse(it) } ?: today,
                    validTo = null,
                ),
            ).id
        }
    }

    /**
     * File absence declarations for a unit — the record a per-person exemption requires (Rule:
     * PM-FEE-007; the exemption itself is PM-FEE-006). Each is a closed span; `filedOn` is
     * stamped from the clock as the Sofia calendar day (PM-SYS-004), so timeliness turns on when
     * the system received the filing, not on a date the caller claims. The unit must exist and
     * belong to the entrance.
     */
    @Transactional
    fun registerAbsence(entranceId: UUID, unitId: UUID, declarations: List<RegisterAbsence>): List<UUID> {
        val unit = units.findById(unitId).orElseThrow { NoSuchElementException("no unit $unitId") }
        if (unit.entranceId != entranceId) {
            throw NoSuchElementException("unit $unitId is not in entrance $entranceId")
        }
        val filedOn = LocalDate.parse(toSofiaDate(clock.instant()))             // a Sofia calendar day (PM-SYS-004)
        return declarations.map { declaration ->
            val from = LocalDate.parse(declaration.absentFrom)
            val to = LocalDate.parse(declaration.absentTo)
            require(to.isAfter(from)) { "absentTo ($to) must be after absentFrom ($from)" }
            aggregates.insert(
                AbsenceDeclaration(
                    id = UUID.randomUUID(),
                    entranceId = entranceId,
                    unitId = unitId,
                    absentFrom = from,
                    absentTo = to,
                    filedOn = filedOn,
                ),
            ).id
        }
    }

    /**
     * Record the day a resident left: their occupancy range closes there, so the fee engine stops
     * counting them from that day (Rule: PM-BOOK-008), and the book's retention window for them
     * starts (PM-BOOK-010). The day is the one declared, not the filing day. A stay already ended is
     * not ended again; the resident must be in the unit and the entrance.
     */
    @Transactional
    fun endHouseholdStay(entranceId: UUID, unitId: UUID, memberId: UUID, on: LocalDate): HouseholdMember {
        val member = household.findById(memberId).orElse(null)
            ?.takeIf { it.entranceId == entranceId && it.unitId == unitId }
            ?: throw NoSuchElementException("no resident $memberId in unit $unitId")
        check(member.validTo == null) { "resident $memberId already left on ${member.validTo}" }
        require(on > member.validFrom) { "the move-out ($on) must be after the move-in (${member.validFrom})" }
        return aggregates.update(member.copy(validTo = on))
    }

    /** Record the day an animal left the unit — its range in the animals section closes (Rule: PM-BOOK-005, PM-BOOK-008). */
    @Transactional
    fun endAnimalStay(entranceId: UUID, unitId: UUID, animalId: UUID, on: LocalDate): Animal {
        val animal = aggregates.findById(animalId, Animal::class.java)
            ?.takeIf { it.entranceId == entranceId && it.unitId == unitId }
            ?: throw NoSuchElementException("no animal $animalId in unit $unitId")
        check(animal.validTo == null) { "animal $animalId already left on ${animal.validTo}" }
        require(on > animal.validFrom) { "the move-out ($on) must be after the move-in (${animal.validFrom})" }
        return aggregates.update(animal.copy(validTo = on))
    }
}
