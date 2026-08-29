package it.alessandropezzali.navguard.integrity;

/** One recorded event. Values that were unknown at the time stay NaN. */
public final class AnomalyEvent {
    public final long timeMs;
    public final AnomalyType type;
    public final double latitude;
    public final double longitude;
    public final float accuracyMeters;
    public final int integrityScore;
    public final String reason;

    public AnomalyEvent(long timeMs, AnomalyType type, double latitude, double longitude,
                        float accuracyMeters, int integrityScore, String reason) {
        this.timeMs = timeMs;
        this.type = type;
        this.latitude = latitude;
        this.longitude = longitude;
        this.accuracyMeters = accuracyMeters;
        this.integrityScore = integrityScore;
        this.reason = reason;
    }

    public boolean hasPosition() {
        return !Double.isNaN(latitude) && !Double.isNaN(longitude);
    }

    public boolean hasAccuracy() {
        return !Float.isNaN(accuracyMeters);
    }
}
