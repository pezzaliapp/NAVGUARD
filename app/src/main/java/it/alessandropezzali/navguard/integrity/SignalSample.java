package it.alessandropezzali.navguard.integrity;

/**
 * Immutable snapshot of the GNSS radio picture at one instant.
 * Unknown values are NaN (doubles) or -1 (counts) so they can be excluded rather than assumed.
 */
public final class SignalSample {
    public final long timeMs;
    public final double avgCn0DbHz;
    public final int satellitesVisible;
    public final int satellitesUsed;
    public final int constellationCount;
    public final double agcDb;

    public SignalSample(long timeMs, double avgCn0DbHz, int satellitesVisible,
                        int satellitesUsed, int constellationCount, double agcDb) {
        this.timeMs = timeMs;
        this.avgCn0DbHz = avgCn0DbHz;
        this.satellitesVisible = satellitesVisible;
        this.satellitesUsed = satellitesUsed;
        this.constellationCount = constellationCount;
        this.agcDb = agcDb;
    }

    public boolean hasCn0() {
        return !Double.isNaN(avgCn0DbHz);
    }

    public boolean hasAgc() {
        return !Double.isNaN(agcDb);
    }

    public boolean hasSatelliteInfo() {
        return satellitesVisible >= 0 && satellitesUsed >= 0;
    }
}
