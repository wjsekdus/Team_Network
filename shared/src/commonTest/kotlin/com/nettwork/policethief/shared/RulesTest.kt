package com.nettwork.policethief.shared

import com.nettwork.policethief.shared.geo.GeoMath
import com.nettwork.policethief.shared.geo.GeoPoint
import com.nettwork.policethief.shared.rules.LocationValidationError
import com.nettwork.policethief.shared.rules.LocationValidator
import com.nettwork.policethief.shared.rules.NicknameError
import com.nettwork.policethief.shared.rules.NicknamePolicy
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocationValidatorTest {
    private val now = 1_800_000_000_000L

    @Test
    fun validLocation_hasNoErrors() {
        assertTrue(LocationValidator.validate(37.5665, 126.9780, 8.0, now - 1_000, now).isEmpty())
    }

    @Test
    fun outOfRangeCoordinates_areRejected() {
        val errors = LocationValidator.validate(91.0, -181.0, 8.0, now, now)
        assertEquals(
            listOf(LocationValidationError.LATITUDE_OUT_OF_RANGE, LocationValidationError.LONGITUDE_OUT_OF_RANGE),
            errors,
        )
    }

    @Test
    fun nanAndNegativeAccuracy_areRejected() {
        assertTrue(LocationValidationError.LATITUDE_OUT_OF_RANGE in LocationValidator.validate(Double.NaN, 127.0, 5.0, now, now))
        assertEquals(listOf(LocationValidationError.ACCURACY_INVALID), LocationValidator.validate(37.0, 127.0, -1.0, now, now))
    }

    @Test
    fun timestampsOutsideWindow_areRejected() {
        val future = now + LocationValidator.MAX_CLOCK_SKEW_MILLIS + 1
        val old = now - LocationValidator.MAX_MEASUREMENT_AGE_MILLIS - 1
        assertEquals(listOf(LocationValidationError.MEASURED_IN_FUTURE), LocationValidator.validate(37.0, 127.0, 5.0, future, now))
        assertEquals(listOf(LocationValidationError.MEASUREMENT_TOO_OLD), LocationValidator.validate(37.0, 127.0, 5.0, old, now))
    }
}

class NicknamePolicyTest {
    @Test
    fun acceptsKoreanEnglishDigits() {
        assertNull(NicknamePolicy.validate("경찰_Kim01"))
    }

    @Test
    fun trimsBeforeValidation() {
        assertNull(NicknamePolicy.validate("  도둑  "))
        assertEquals("도둑", NicknamePolicy.normalize("  도둑  "))
    }

    @Test
    fun rejectsInvalidValues() {
        assertEquals(NicknameError.BLANK, NicknamePolicy.validate("   "))
        assertEquals(NicknameError.TOO_SHORT, NicknamePolicy.validate("a"))
        assertEquals(NicknameError.TOO_LONG, NicknamePolicy.validate("a".repeat(NicknamePolicy.MAX_LENGTH + 1)))
        assertEquals(NicknameError.INVALID_CHARACTERS, NicknamePolicy.validate("hi there"))
        assertEquals(NicknameError.INVALID_CHARACTERS, NicknamePolicy.validate("<script>"))
    }
}

class GeoMathTest {
    private val cityHall = GeoPoint(37.5665, 126.9780)

    @Test
    fun distanceOfOneThousandthDegreeLatitude_isAbout111m() {
        val north = cityHall.copy(latitude = cityHall.latitude + 0.001)
        val distance = GeoMath.distanceMeters(cityHall, north)
        assertTrue(abs(distance - 111.2) < 1.0, "실제 거리: $distance")
    }

    @Test
    fun jailRadiusCheck() {
        val fiveMetersNorth = cityHall.copy(latitude = cityHall.latitude + 5.0 / 111_195.0)
        val twentyMetersNorth = cityHall.copy(latitude = cityHall.latitude + 20.0 / 111_195.0)
        assertTrue(GeoMath.isWithinRadius(fiveMetersNorth, cityHall, 10.0))
        assertFalse(GeoMath.isWithinRadius(twentyMetersNorth, cityHall, 10.0))
    }

    @Test
    fun centroidOfFourPlayers_isAverage() {
        val points = listOf(GeoPoint(37.0, 127.0), GeoPoint(37.002, 127.0), GeoPoint(37.0, 127.002), GeoPoint(37.002, 127.002))
        val center = GeoMath.centroid(points)
        assertEquals(37.001, center.latitude, absoluteTolerance = 1e-9)
        assertEquals(127.001, center.longitude, absoluteTolerance = 1e-9)
    }

    @Test
    fun circlePolygon_pointsAreOnRadius_andClosed() {
        val ring = GeoMath.circlePolygon(cityHall, 500.0, segments = 32)
        assertEquals(33, ring.size)
        assertEquals(ring.first(), ring.last())
        ring.forEach { assertTrue(abs(GeoMath.distanceMeters(cityHall, it) - 500.0) < 0.5) }
    }
}
