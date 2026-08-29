package it.alessandropezzali.navguard.integrity;

/**
 * Immutable, Android-free snapshot of a GNSS fix.
 * Unknown fields are represented as NaN so the engine can exclude them instead of assuming zero.
 */
public final class PositionFix {
    public final double latitude;
    public final double longitude;
    public final float accuracyMeters;
    public final float speedMps;
    public final float bearingDeg;
    public final long timeMs;

    public PositionFix(double latitude, double longitude, float accuracyMeters,
                       float speedMps, float bearingDeg, long timeMs) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.accuracyMeters = accuracyMeters;
        this.speedMps = speedMps;
        this.bearingDeg = bearingDeg;
        this.timeMs = timeMs;
    }

    public boolean hasAccuracy() {
        return !Float.isNaN(accuracyMeters) && accuracyMeters > 0f;
    }

    public boolean hasSpeed() {
        return !Float.isNaN(speedMps);
    }

    public boolean hasBearing() {
        return !Float.isNaN(bearingDeg);
    }
}
