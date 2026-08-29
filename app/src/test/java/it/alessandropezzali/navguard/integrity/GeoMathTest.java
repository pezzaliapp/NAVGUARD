package it.alessandropezzali.navguard.integrity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GeoMathTest {

    @Test
    public void distanceIsZeroForTheSamePoint() {
        assertEquals(0.0, GeoMath.distanceMeters(45.4640, 9.1900, 45.4640, 9.1900), 1e-6);
    }

    @Test
    public void oneDegreeOfLatitudeIsAboutOneHundredElevenKilometres() {
        double d = GeoMath.distanceMeters(45.0, 9.0, 46.0, 9.0);
        assertEquals(111_195.0, d, 500.0);
    }

    @Test
    public void shortDistancesMatchTheFlatEarthApproximation() {
        double expected = 500.0;
        double d = GeoMath.distanceMeters(45.4640, 9.1900,
                45.4640 + TestSupport.latOffsetForMeters(expected), 9.1900);
        assertEquals(expected, d, 5.0);
    }

    @Test
    public void distanceIsSymmetric() {
        double a = GeoMath.distanceMeters(45.0, 9.0, 45.5, 9.5);
        double b = GeoMath.distanceMeters(45.5, 9.5, 45.0, 9.0);
        assertEquals(a, b, 1e-6);
    }

    @Test
    public void bearingDeltaWrapsAroundNorth() {
        assertEquals(0.0, GeoMath.bearingDeltaDeg(10.0, 10.0), 1e-9);
        assertEquals(20.0, GeoMath.bearingDeltaDeg(350.0, 10.0), 1e-9);
        assertEquals(180.0, GeoMath.bearingDeltaDeg(0.0, 180.0), 1e-9);
        assertTrue(GeoMath.bearingDeltaDeg(0.0, 200.0) <= 180.0);
    }

    @Test
    public void angleBetweenVectorsIsDegreesAndNaNSafe() {
        assertEquals(90.0, GeoMath.angleBetweenDeg(new float[]{1, 0, 0}, new float[]{0, 1, 0}), 1e-6);
        assertEquals(0.0, GeoMath.angleBetweenDeg(new float[]{2, 0, 0}, new float[]{5, 0, 0}), 1e-6);
        assertEquals(180.0, GeoMath.angleBetweenDeg(new float[]{1, 0, 0}, new float[]{-1, 0, 0}), 1e-6);
        assertTrue(Double.isNaN(GeoMath.angleBetweenDeg(null, new float[]{1, 0, 0})));
        assertTrue(Double.isNaN(GeoMath.angleBetweenDeg(new float[]{0, 0, 0}, new float[]{1, 0, 0})));
    }

    @Test
    public void clampKeepsValuesInRange() {
        assertEquals(5.0, GeoMath.clamp(5.0, 0.0, 10.0), 1e-9);
        assertEquals(0.0, GeoMath.clamp(-3.0, 0.0, 10.0), 1e-9);
        assertEquals(10.0, GeoMath.clamp(42.0, 0.0, 10.0), 1e-9);
    }

    @Test
    public void positionFixReportsMissingFieldsHonestly() {
        PositionFix full = new PositionFix(45.0, 9.0, 5f, 2f, 90f, 1000L);
        assertTrue(full.hasAccuracy());
        assertTrue(full.hasSpeed());
        assertTrue(full.hasBearing());

        PositionFix bare = new PositionFix(45.0, 9.0, Float.NaN, Float.NaN, Float.NaN, 1000L);
        assertTrue(!bare.hasAccuracy());
        assertTrue(!bare.hasSpeed());
        assertTrue(!bare.hasBearing());
    }
}
