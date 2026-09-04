package com.minesafear.ui.modules.fire

import androidx.annotation.RawRes
import androidx.annotation.StringRes
import com.minesafear.R
import com.minesafear.ar.ArModels

/**
 * The three extinguisher classes a trainee has to tell apart.
 *
 * Nothing here says which one is *correct* — that depends on what is burning, and
 * belongs to [FireScenario]. The whole point of the module is that the answer
 * changes with the fire while the extinguishers stay the same.
 *
 * Body colour is the identifying feature, so the placeholder models are colour
 * coded to match the real standard (IS 15683 / EN 3): red for water, cream for
 * foam, black for CO2. Swapping in a model of the wrong colour breaks the
 * training, not just the looks.
 */
enum class ExtinguisherType(
    @RawRes val modelRes: Int,
    @StringRes val labelRes: Int,
) {
    CO2(
        modelRes = ArModels.EXTINGUISHER_CO2,
        labelRes = R.string.fire_module_item_extinguisher_co2,
    ),
    FOAM(
        modelRes = ArModels.EXTINGUISHER_FOAM,
        labelRes = R.string.fire_module_item_extinguisher_foam,
    ),
    WATER(
        modelRes = ArModels.EXTINGUISHER_WATER,
        labelRes = R.string.fire_module_item_extinguisher_water,
    ),
}

/**
 * One fire, one right answer, and a reason for every wrong one.
 *
 * Scenarios are data rather than code so that the next one — a fuel spill under a
 * loader, say, where foam is right and CO2 is nearly useless — is a new instance
 * and not a new screen.
 */
class FireScenario(
    /** Stable id written to `module_results.module_id`. Never localise this. */
    val moduleId: String,
    @StringRes val titleRes: Int,
    /** What is burning. Shown throughout the drill, not just in the briefing. */
    @StringRes val fireDescriptionRes: Int,
    /** Briefing lines, in order. */
    val briefingRes: List<Int>,
    val correctExtinguisher: ExtinguisherType,
    /**
     * Why each of the other extinguishers is wrong *for this fire*. Values are
     * `@StringRes`; Kotlin cannot annotate a map's values.
     *
     * Must cover every [ExtinguisherType] except [correctExtinguisher] — see
     * [validate].
     */
    val wrongExtinguisherReasons: Map<ExtinguisherType, Int>,
    /** How many escape routes the trainee places, one of which is signed. */
    val routeCount: Int,
) {
    /** Extinguishers to place, in a random order so the answer is not positional. */
    fun extinguisherOrder(): List<ExtinguisherType> = ExtinguisherType.entries.shuffled()

    @StringRes
    fun wrongReasonFor(type: ExtinguisherType): Int? = wrongExtinguisherReasons[type]

    /**
     * Fails fast on a scenario that cannot be completed, rather than showing a
     * trainee a blank explanation overlay when they pick the wrong bottle.
     */
    private fun validate() {
        require(routeCount >= 2) { "a route choice needs at least one decoy" }
        require(correctExtinguisher !in wrongExtinguisherReasons) {
            "$correctExtinguisher is both the answer and a wrong answer"
        }
        val unexplained = ExtinguisherType.entries - correctExtinguisher - wrongExtinguisherReasons.keys
        require(unexplained.isEmpty()) { "no explanation for wrong choices: $unexplained" }
    }

    init {
        validate()
    }
}

/** Every fire scenario the app ships. */
object FireScenarios {

    /**
     * A switchgear fire — the "Fire & Explosion Response" module.
     *
     * Electrical, which makes it the sharpest possible lesson: the two
     * extinguishers a worker is most likely to grab are both water-based and both
     * conduct, so the intuitive answer is the one that electrocutes you. CO2 is
     * correct because it is non-conductive and leaves no residue on the equipment.
     */
    val ELECTRICAL_SWITCHGEAR = FireScenario(
        moduleId = "fire_explosion_response",
        titleRes = R.string.fire_module_title,
        fireDescriptionRes = R.string.fire_module_fire_description,
        briefingRes = listOf(
            R.string.fire_module_instruction_1,
            R.string.fire_module_instruction_2,
            R.string.fire_module_instruction_3,
        ),
        correctExtinguisher = ExtinguisherType.CO2,
        wrongExtinguisherReasons = mapOf(
            ExtinguisherType.WATER to R.string.fire_module_wrong_water,
            ExtinguisherType.FOAM to R.string.fire_module_wrong_foam,
        ),
        routeCount = 3,
    )
}
