package it.alessandropezzali.navguard.integrity;

/**
 * How far one {@link SignalSample} departs from the recent local baseline.
 * NAVGUARD never treats a single absolute C/N0 or AGC reading as evidence of interference:
 * what matters is the change relative to what this device was just seeing.
 */
public final class SignalDeviation {
    public final boolean baselineReady;
    public final double cn0Drop;
    public final double satellitesUsedDrop;
    public final double agcShift;
    public final boolean cn0Anomaly;
    public final boolean satellitesAnomaly;
    public final boolean agcAnomaly;

    public SignalDeviation(boolean baselineReady, double cn0Drop, double satellitesUsedDrop,
                           double agcShift, boolean cn0Anomaly, boolean satellitesAnomaly,
                           boolean agcAnomaly) {
        this.baselineReady = baselineReady;
        this.cn0Drop = cn0Drop;
        this.satellitesUsedDrop = satellitesUsedDrop;
        this.agcShift = agcShift;
        this.cn0Anomaly = cn0Anomaly;
        this.satellitesAnomaly = satellitesAnomaly;
        this.agcAnomaly = agcAnomaly;
    }

    public static SignalDeviation notReady() {
        return new SignalDeviation(false, Double.NaN, Double.NaN, Double.NaN, false, false, false);
    }

    /** Number of simultaneously flagged radio parameters (0..3). */
    public int anomalyCount() {
        int n = 0;
        if (cn0Anomaly) n++;
        if (satellitesAnomaly) n++;
        if (agcAnomaly) n++;
        return n;
    }
}
