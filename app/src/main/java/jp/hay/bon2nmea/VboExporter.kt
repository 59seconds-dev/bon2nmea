package jp.hay.bon2nmea

import java.util.Locale

/**
 * Racelogic VBO GPS serializer.
 * VBO lat/long are signed total arcminutes, not decimal degrees.
 * BON elevation scaling is unknown, so output zero rather than a false altitude.
 */
object VboExporter {
    fun line(point: LocationData): String {
        val t = point.m_timestamp
        val time = String.format(Locale.US, "%02d%02d%02d.%03d",
            t.hour, t.minute, t.second, t.nano / 1_000_000)
        // BON coordinates are in millionths of arcminutes.
        val latitudeMinutes = point.m_latitude / 1_000_000.0
        val longitudeMinutes = point.m_longtitude / 1_000_000.0
        val speedKmh = point.m_speed * 0.036 // BON speed is cm/s
        val heading = point.m_course / 100.0
        // BON does not provide satellite count; use a documented compatibility placeholder.\n        return String.format(Locale.US, "008 %s %+.6f %+.6f %.2f %.2f +0000.00",
            time, latitudeMinutes, longitudeMinutes, speedKmh, heading)
    }
}
