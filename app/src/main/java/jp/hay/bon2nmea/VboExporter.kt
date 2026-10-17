package jp.hay.bon2nmea

import java.util.Locale

/**
 * Racelogic VBO GPS serializer, using RaceChrono-exported VBO conventions.
 * Latitude is positive north, longitude is negative east, both in total arcminutes.
 */
object VboExporter {
    fun line(point: LocationData): String {
        val t = point.m_timestamp
        val time = String.format(Locale.US, "%02d%02d%02d.%02d",
            t.hour, t.minute, t.second, t.nano / 10_000_000)
        val latitudeMinutes = point.m_latitude / 1_000_000.0
        val longitudeMinutes = -point.m_longtitude / 1_000_000.0
        val speedKmh = point.m_speed * 0.036 // BON speed: cm/s
        val heading = point.m_course / 100.0
        // Satellite count is unavailable in BON. Use 000 as RaceChrono does
        // when exporting this same source; do not invent an actual fix count.
        return String.format(Locale.US,
            "000 %s %+012.6f %+013.6f %07.3f %06.2f +00000.00",
            time, latitudeMinutes, longitudeMinutes, speedKmh, heading)
    }
}
