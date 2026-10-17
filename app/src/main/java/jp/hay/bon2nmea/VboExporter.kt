package jp.hay.bon2nmea

import java.util.Locale

/**
 * Experimental VBO serializer.
 * Elevation unit/scale in BON4 has NOT been independently verified.
 * RaceChrono import compatibility must be tested before treating VBO as stable.
 */
object VboExporter {
    fun line(point: LocationData): String {
        val t = point.m_timestamp
        val time = String.format(Locale.US, "%02d%02d%02d.%03d",
            t.hour, t.minute, t.second, t.nano / 1_000_000)
        // BON coordinates are stored as millionths of arcminutes.
        val latitude = point.m_latitude / 60_000_000.0
        val longitude = point.m_longtitude / 60_000_000.0
        // Original NMEA code converts m_speed by 0.0194384 to knots,
        // implying BON speed is hundredths of km/h.
        val speedKmh = point.m_speed / 100.0
        val heading = point.m_course / 100.0
        return String.format(Locale.US, "0 %s %.8f %.8f %.2f %.2f %.1f",
            time, latitude, longitude, speedKmh, heading, point.m_elevation.toDouble())
    }
}
