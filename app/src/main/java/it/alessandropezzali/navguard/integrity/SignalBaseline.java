package it.alessandropezzali.navguard.integrity;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Rolling, in-memory baseline of the local radio picture.
 *
 * Nothing is persisted and nothing leaves the device. The baseline exists so that NAVGUARD can
 * look at CHANGES rather than at absolute values: an urban canyon and a jammed band both show a
 * low C/N0, but only the second one shows a fast, simultaneous shift of several parameters.
 */
public final class SignalBaseline {
    /** Hard cap on retained samples. */
    public static final int MAX_SAMPLES = 24;
    /** Samples older than this are dropped. */
    public static final long WINDOW_MS = 120_000L;
    /** Below this many samples the baseline refuses to judge. */
    public static final int MIN_SAMPLES_FOR_BASELINE = 6;

    /** C/N0 loss versus baseline, in dB-Hz, that flags the signal. */
    public static final double CN0_DROP_DB = 6.0;
    /** Absolute AGC shift versus baseline, in dB, that flags the receiver front end. */
    public static final double AGC_SHIFT_DB = 5.0;
    /** Both conditions must hold for a satellite anomaly: absolute drop and relative drop. */
    public static final int SATS_USED_ABS_DROP = 3;
    public static final double SATS_USED_DROP_RATIO = 0.5;

    private final Deque<SignalSample> samples = new ArrayDeque<>();

    public void add(SignalSample sample) {
        if (sample == null) return;
        samples.addLast(sample);
        prune(sample.timeMs);
    }

    private void prune(long nowMs) {
        while (!samples.isEmpty() && nowMs - samples.peekFirst().timeMs > WINDOW_MS) {
            samples.removeFirst();
        }
        while (samples.size() > MAX_SAMPLES) {
            samples.removeFirst();
        }
    }

    public int size() {
        return samples.size();
    }

    public boolean isReady() {
        return samples.size() >= MIN_SAMPLES_FOR_BASELINE;
    }

    public void clear() {
        samples.clear();
    }

    /** Mean C/N0 over the window, NaN when no sample carries it. */
    public double baselineCn0() {
        double sum = 0.0;
        int n = 0;
        for (SignalSample s : samples) {
            if (s.hasCn0()) {
                sum += s.avgCn0DbHz;
                n++;
            }
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    /** Mean number of satellites used over the window, NaN when unknown. */
    public double baselineSatellitesUsed() {
        double sum = 0.0;
        int n = 0;
        for (SignalSample s : samples) {
            if (s.hasSatelliteInfo()) {
                sum += s.satellitesUsed;
                n++;
            }
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    /** Mean AGC over the window, NaN when the device never reported it. */
    public double baselineAgc() {
        double sum = 0.0;
        int n = 0;
        for (SignalSample s : samples) {
            if (s.hasAgc()) {
                sum += s.agcDb;
                n++;
            }
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    /**
     * Compares a candidate sample with the current baseline WITHOUT adding it.
     * Call this first, then {@link #add(SignalSample)}, so a sample never dilutes its own baseline.
     */
    public SignalDeviation evaluate(SignalSample candidate) {
        if (candidate == null || !isReady()) return SignalDeviation.notReady();

        double baseCn0 = baselineCn0();
        double baseSats = baselineSatellitesUsed();
        double baseAgc = baselineAgc();

        double cn0Drop = Double.NaN;
        boolean cn0Anomaly = false;
        if (!Double.isNaN(baseCn0) && candidate.hasCn0()) {
            cn0Drop = baseCn0 - candidate.avgCn0DbHz;
            cn0Anomaly = cn0Drop >= CN0_DROP_DB;
        }

        double satsDrop = Double.NaN;
        boolean satsAnomaly = false;
        if (!Double.isNaN(baseSats) && candidate.hasSatelliteInfo()) {
            satsDrop = baseSats - candidate.satellitesUsed;
            satsAnomaly = satsDrop >= SATS_USED_ABS_DROP
                    && baseSats > 0
                    && candidate.satellitesUsed < baseSats * SATS_USED_DROP_RATIO;
        }

        double agcShift = Double.NaN;
        boolean agcAnomaly = false;
        if (!Double.isNaN(baseAgc) && candidate.hasAgc()) {
            agcShift = Math.abs(candidate.agcDb - baseAgc);
            agcAnomaly = agcShift >= AGC_SHIFT_DB;
        }

        return new SignalDeviation(true, cn0Drop, satsDrop, agcShift,
                cn0Anomaly, satsAnomaly, agcAnomaly);
    }
}
