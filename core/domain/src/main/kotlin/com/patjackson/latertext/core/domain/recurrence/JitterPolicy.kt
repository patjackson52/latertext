package com.patjackson.latertext.core.domain.recurrence

import com.patjackson.latertext.core.model.RecurrenceRule
import java.time.Duration
import java.time.ZoneId
import kotlin.random.Random

fun interface JitterRandom {
    /** Returns a discrete uniform whole minute in the inclusive range. */
    fun nextInt(fromInclusive: Int, toInclusive: Int): Int
}

class DefaultJitterRandom(
    private val random: Random = Random.Default,
) : JitterRandom {
    override fun nextInt(fromInclusive: Int, toInclusive: Int): Int {
        require(fromInclusive <= toInclusive)
        return random.nextInt(fromInclusive, toInclusive + 1)
    }
}

data class JitterValidation(
    val valid: Boolean,
    val configuredRangeMinutes: Int,
    val maximumSafeRangeMinutes: Int?,
)

class InvalidJitterRangeException(
    val validation: JitterValidation,
) : IllegalArgumentException(
    "Jitter range ${validation.configuredRangeMinutes} minutes can overlap or reorder adjacent occurrences; " +
        "maximum safe range is ${validation.maximumSafeRangeMinutes}",
)

class JitterPolicyValidator(
    private val generator: RecurrenceGenerator = RecurrenceGenerator(),
    private val resolver: CivilTimeResolver = CivilTimeResolver(),
) {
    /**
     * Samples up to [sampleLimit] emitted slots. Supported v1 recurrence frequencies repeat well
     * inside this window, including the Gregorian 400-year leap pattern for monthly day rules.
     */
    fun validate(
        rule: RecurrenceRule,
        activeDeviceZone: ZoneId,
        sampleLimit: Int = 4_800,
    ): JitterValidation {
        require(sampleLimit >= 2)
        if (rule.jitterRangeMinutes == 0) {
            return JitterValidation(true, 0, maximumSafeRangeMinutes(rule, activeDeviceZone, sampleLimit))
        }
        val maximum = maximumSafeRangeMinutes(rule, activeDeviceZone, sampleLimit)
        return JitterValidation(
            valid = maximum == null || rule.jitterRangeMinutes <= maximum,
            configuredRangeMinutes = rule.jitterRangeMinutes,
            maximumSafeRangeMinutes = maximum,
        )
    }

    private fun maximumSafeRangeMinutes(
        rule: RecurrenceRule,
        activeDeviceZone: ZoneId,
        sampleLimit: Int,
    ): Int? {
        val zone = resolver.selectedZone(rule, activeDeviceZone)
        val instants = generator.slots(rule)
            .take(sampleLimit)
            .map { resolver.resolve(it.nominalLocalDateTime, zone).instant }
            .toList()
        if (instants.size < 2) return null
        val minimumGapSeconds = instants.zipWithNext { first, second ->
            Duration.between(first, second).seconds
        }.minOrNull() ?: return null
        check(minimumGapSeconds > 0) { "Recurrence slots must be strictly ordered" }
        // Strict inequality prevents both extreme offsets from landing on the same instant.
        return ((minimumGapSeconds - 1) / 120L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
