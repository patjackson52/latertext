package com.patjackson.latertext.platform.android

import com.patjackson.latertext.platform.api.AppClock
import com.patjackson.latertext.platform.api.DeviceZoneProvider
import com.patjackson.latertext.platform.api.RandomIntSource
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId

class SystemAppClock : AppClock {
    override fun now(): Instant = Instant.now()
}

class SystemDeviceZoneProvider : DeviceZoneProvider {
    override fun currentZone(): ZoneId = ZoneId.systemDefault()
}

class SecureRandomIntSource(
    private val random: SecureRandom = SecureRandom(),
) : RandomIntSource {
    override fun nextIntInclusive(minimum: Int, maximum: Int): Int {
        require(minimum <= maximum) { "minimum must be <= maximum" }
        return minimum + random.nextInt(maximum - minimum + 1)
    }
}
