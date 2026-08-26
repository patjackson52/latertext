package com.patjackson.latertext.platform.api

import java.time.Instant
import java.time.ZoneId

/** Injectable wall-clock boundary. Pure callers must not read system time directly. */
fun interface AppClock {
    fun now(): Instant
}

/** Supplies the current device zone without coupling domain code to Android. */
fun interface DeviceZoneProvider {
    fun currentZone(): ZoneId
}

/** Deterministic in tests; implementations return a value from the inclusive range. */
fun interface RandomIntSource {
    fun nextIntInclusive(minimum: Int, maximum: Int): Int
}

enum class AlarmPrecision {
    EXACT,
    INEXACT,
}

data class AlarmRequest(
    val occurrenceId: String,
    val generation: Long,
    val triggerAt: Instant,
    val precision: AlarmPrecision,
)

interface AlarmDriver {
    fun canScheduleExactAlarms(): Boolean
    fun arm(request: AlarmRequest)
    fun cancel(occurrenceId: String, generation: Long)
}
