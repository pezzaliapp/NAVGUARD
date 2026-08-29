package it.alessandropezzali.navguard.integrity;

/**
 * Pure geodesic helpers, kept free of Android APIs so the integrity logic stays unit-testable.
 */
public final class GeoMath {
    /** IUGG mean Earth radius. */
    public static final double EARTH_RADIUS_M = 6371008.8;

    private GeoMath() {
    }

    /** Great-circle distance in metres (haversine). */
    public static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double phi1 = Math.toRadians(lat1);
        double phi2 = Math.toRadians(lat2);
        double dPhi = Math.toRadians(lat2 - lat1);
        double dLambda = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dPhi / 2) * Math.sin(dPhi / 2)
                + Math.cos(phi1) * Math.cos(phi2) * Math.sin(dLambda / 2) * Math.sin(dLambda / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0.0, 1 - a)));
        return EARTH_RADIUS_M * c;
    }

    /** Smallest absolute angle between two bearings, in degrees (0..180). */
    public static double bearingDeltaDeg(double a, double b) {
        double d = Math.abs(a - b) % 360.0;
        return d > 180.0 ? 360.0 - d : d;
    }

    /** Angle between two 3D vectors in degrees; NaN when either vector is degenerate. */
    public static double angleBetweenDeg(float[] a, float[] b) {
        if (a == null || b == null || a.length < 3 || b.length < 3) return Double.NaN;
        double na = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        double nb = Math.sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2]);
        if (na < 1e-6 || nb < 1e-6) return Double.NaN;
        double dot = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (na * nb);
        return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
    }

    public static double clamp(double v, double min, double max) {
        return v < min ? min : (v > max ? max : v);
    }
}
