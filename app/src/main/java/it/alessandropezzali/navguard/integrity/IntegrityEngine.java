package it.alessandropezzali.navguard.integrity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The GNSS integrity engine.
 *
 * It is deliberately free of Android APIs: everything it needs arrives as plain value objects
 * ({@link PositionFix}, {@link SignalSample}) so the whole scoring chain can be unit tested
 * without a real GNSS receiver.
 *
 * The engine answers one question: how CONSISTENT is what the GNSS reports with the signal
 * picture, the satellite picture, the recent history of positions and the independent sensors
 * available on this device. It never claims spoofing or jamming, and it never claims to know
 * the cause of an inconsistency.
 *
 * Five sub-scores are produced, each on 0..100, and combined with fixed documented weights.
 * A sub-score that cannot be measured is UNAVAILABLE, never zero: it is dropped from the
 * weighted mean and the remaining weights are renormalised over what is actually available.
 */
public final class IntegrityEngine {

    // ------------------------------------------------------------------
    // Component names
    // ------------------------------------------------------------------
    public static final String SIGNAL_QUALITY = "SIGNAL QUALITY";
    public static final String SATELLITE_QUALITY = "SATELLITE QUALITY";
    public static final String POSITION_CONSISTENCY = "POSITION CONSISTENCY";
    public static final String MOTION_CONSISTENCY = "MOTION CONSISTENCY";
    public static final String RAW_GNSS_AVAILABILITY = "RAW GNSS AVAILABILITY";

    // ------------------------------------------------------------------
    // Weights. Rationale:
    //  - position consistency is the strongest single piece of evidence a phone can produce,
    //    so it carries the largest share;
    //  - signal and satellite quality describe the reception environment and together weigh as
    //    much as position;
    //  - motion consistency is powerful but only usable when the IMU can conclude, hence 20%;
    //  - raw GNSS availability is a capability indicator, not a measurement, hence only 5%.
    // ------------------------------------------------------------------
    public static final double W_SIGNAL = 0.25;
    public static final double W_SATELLITE = 0.20;
    public static final double W_POSITION = 0.30;
    public static final double W_MOTION = 0.20;
    public static final double W_RAW = 0.05;

    // ------------------------------------------------------------------
    // Signal quality thresholds
    // ------------------------------------------------------------------
    public static final double CN0_VERY_LOW_DBHZ = 15.0;
    public static final double CN0_LOW_DBHZ = 22.0;
    public static final double SIGNAL_PENALTY_CN0_VERY_LOW = 30.0;
    public static final double SIGNAL_PENALTY_CN0_LOW = 15.0;
    /** One isolated deviation is weak evidence; simultaneous deviations are what matters. */
    public static final double SIGNAL_PENALTY_ONE_DEVIATION = 10.0;
    public static final double SIGNAL_PENALTY_TWO_DEVIATIONS = 30.0;
    public static final double SIGNAL_PENALTY_THREE_DEVIATIONS = 50.0;

    // ------------------------------------------------------------------
    // Satellite quality thresholds
    // ------------------------------------------------------------------
    public static final int SATS_USED_WEAK_FIX = 4;
    public static final int SATS_USED_COMFORTABLE = 6;
    public static final int SATS_VISIBLE_LOW = 6;
    public static final double SAT_PENALTY_WEAK_FIX = 45.0;
    public static final double SAT_PENALTY_THIN_FIX = 20.0;
    public static final double SAT_PENALTY_FEW_VISIBLE = 20.0;
    public static final double SAT_PENALTY_SINGLE_CONSTELLATION = 10.0;
    public static final double SAT_BONUS_MULTI_CONSTELLATION = 5.0;

    // ------------------------------------------------------------------
    // Position consistency thresholds
    // ------------------------------------------------------------------
    /** Assumed accuracy when the fix does not declare one. */
    public static final double DEFAULT_ACCURACY_M = 30.0;
    /** Floor of the combined positional uncertainty of two fixes. */
    public static final double MIN_SIGMA_M = 10.0;
    /** At or below this accuracy a jump is taken at full weight. */
    public static final double GOOD_ACCURACY_M = 20.0;
    /** Accuracy range over which the weight of a jump fades away. */
    public static final double ACCURACY_DAMP_SPAN_M = 80.0;
    /** A jump never counts for less than this fraction, however bad the accuracy. */
    public static final double MIN_ACCURACY_FACTOR = 0.25;
    /** Plausible ground speed while the IMU reports STATIONARY. */
    public static final double STATIONARY_PLAUSIBLE_SPEED_MPS = 1.5;
    /** Assumed speed while MOVING when neither fix declares one. */
    public static final double MOVING_DEFAULT_SPEED_MPS = 20.0;
    public static final double MOVING_SPEED_MARGIN = 1.6;
    public static final double MOVING_SPEED_OFFSET_MPS = 5.0;
    public static final double MOVING_MIN_PLAUSIBLE_MPS = 8.0;
    /** With an inconclusive IMU only outright teleports are flagged. */
    public static final double UNKNOWN_PLAUSIBLE_SPEED_MPS = 60.0;
    public static final double SPEED_CROSSCHECK_FLOOR_MPS = 5.0;
    public static final double SPEED_CROSSCHECK_PENALTY = 15.0;
    /** Position penalty at or above which a POSITION_JUMP / GNSS_IMU_CONFLICT is logged. */
    public static final double POSITION_JUMP_EVENT_PENALTY = 40.0;

    // ------------------------------------------------------------------
    // Motion consistency thresholds
    // ------------------------------------------------------------------
    /** GNSS speed at or above which the receiver is claiming real movement. */
    public static final double SPEED_MOVING_MPS = 1.5;
    public static final long SPEED_CONFLICT_MS = 4000L;
    public static final long SPEED_CONFLICT_SEVERE_MS = 8000L;
    public static final double MOTION_SCORE_CONFLICT_START = 70.0;
    public static final double MOTION_SCORE_CONFLICT = 25.0;
    public static final double MOTION_SCORE_CONFLICT_SEVERE = 0.0;
    /** Heading is secondary evidence only, and never worth more than this many points. */
    public static final double HEADING_MAX_PENALTY = 10.0;
    public static final double HEADING_MIN_SPEED_MPS = 3.0;
    public static final double HEADING_MAX_DEVICE_ROTATION_DEG = 20.0;
    public static final double HEADING_MIN_BEARING_CHANGE_DEG = 60.0;
    public static final double HEADING_FULL_BEARING_CHANGE_DEG = 120.0;
    public static final double HEADING_MIN_FACTOR = 0.2;

    // ------------------------------------------------------------------
    // Raw GNSS availability
    // ------------------------------------------------------------------
    /** Raw measurements are not expected instantly; nothing is judged inside this window. */
    public static final long RAW_GRACE_MS = 30_000L;
    /** Score when the platform declares raw support but no measurement ever arrives. */
    public static final double RAW_MISSING_SCORE = 40.0;

    // ------------------------------------------------------------------
    // Events and trusted position
    // ------------------------------------------------------------------
    public static final long GNSS_LOST_MS = 10_000L;
    public static final long SIGNAL_EVENT_COOLDOWN_MS = 20_000L;
    public static final int LAST_TRUSTED_MIN_SCORE = 80;
    /** No position/motion component may be below this for a fix to become the trusted one. */
    public static final double TRUSTED_MIN_COMPONENT = 70.0;

    // ------------------------------------------------------------------
    // Integrity caps.
    //
    // A plain weighted mean cannot express "one component failed catastrophically": with
    // position consistency worth 30%, a total position failure would still leave the overall
    // score at 70, i.e. NORMALE, which would be misleading. The weights are therefore kept as
    // documented and a severe inconsistency instead CAPS the final score into the band that
    // honestly describes it. Caps only ever lower the score, never raise it.
    // ------------------------------------------------------------------
    /** Position consistency at or below this value counts as a severe inconsistency. */
    public static final double SEVERE_POSITION_THRESHOLD = 20.0;
    /** Motion consistency at or below this value counts as a severe inconsistency. */
    public static final double SEVERE_MOTION_THRESHOLD = 25.0;
    /** Severe position inconsistency alone: at most ATTENZIONE. */
    public static final int CAP_SEVERE_POSITION = 45;
    /** Severe position inconsistency while the sensors report STATIONARY: at most ANOMALIA. */
    public static final int CAP_POSITION_VS_STATIONARY = 35;
    /** Persistent GNSS/IMU speed conflict: at most ATTENZIONE. */
    public static final int CAP_SEVERE_MOTION = 45;
    /** Both position and motion severely inconsistent: at most ANOMALIA. */
    public static final int CAP_SEVERE_BOTH = 25;

    public static final int MAX_REASONS = 3;

    // ------------------------------------------------------------------
    // Collaborators
    // ------------------------------------------------------------------
    private final MotionAnalyzer motionAnalyzer;
    private final SignalBaseline signalBaseline;
    private final AnomalyLog anomalyLog;

    // ------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------
    private boolean providerEnabled = true;
    private long monitoringStartMs = 0L;
    private Boolean rawSupported = null;
    private boolean rawSeen = false;
    private int rawMeasurementCount = 0;

    private SignalSample lastSignal;
    private SignalDeviation lastDeviation = SignalDeviation.notReady();

    private PositionFix previousFix;
    private PositionFix currentFix;
    private SubScore positionScore = SubScore.unavailable(POSITION_CONSISTENCY);
    private double lastPositionPenalty = 0.0;
    private double lastJumpDistanceM = 0.0;
    private double lastDtSeconds = 0.0;
    private double lastImpliedSpeedMps = 0.0;
    private MotionState lastFixMotionState = MotionState.UNKNOWN;

    private long speedConflictStartMs = 0L;
    private long speedConflictMs = 0L;
    private boolean speedConflictReported = false;
    private double headingPenalty = 0.0;

    private boolean gnssLost = false;
    private long lastSignalAnomalyEventMs = Long.MIN_VALUE;

    private PositionFix lastTrustedFix;
    private long lastTrustedTimeMs = 0L;

    public IntegrityEngine() {
        this(new MotionAnalyzer(), new AnomalyLog());
    }

    public IntegrityEngine(MotionAnalyzer motionAnalyzer, AnomalyLog anomalyLog) {
        this.motionAnalyzer = motionAnalyzer == null ? new MotionAnalyzer() : motionAnalyzer;
        this.anomalyLog = anomalyLog == null ? new AnomalyLog() : anomalyLog;
        this.signalBaseline = new SignalBaseline();
    }

    public MotionAnalyzer motionAnalyzer() {
        return motionAnalyzer;
    }

    public SignalBaseline signalBaseline() {
        return signalBaseline;
    }

    public AnomalyLog anomalyLog() {
        return anomalyLog;
    }

    public SignalDeviation lastDeviation() {
        return lastDeviation;
    }

    public SignalSample lastSignal() {
        return lastSignal;
    }

    public PositionFix lastTrustedFix() {
        return lastTrustedFix;
    }

    public long lastTrustedTimeMs() {
        return lastTrustedTimeMs;
    }

    public PositionFix currentFix() {
        return currentFix;
    }

    public boolean isGnssLost() {
        return gnssLost;
    }

    public MotionState motionStateAt(long nowMs) {
        return motionAnalyzer.stateAt(nowMs);
    }

    // ------------------------------------------------------------------
    // Inputs
    // ------------------------------------------------------------------

    public void onMonitoringStarted(long nowMs) {
        monitoringStartMs = nowMs;
    }

    public void setProviderEnabled(boolean enabled) {
        providerEnabled = enabled;
    }

    /**
     * Declares whether this platform supports raw GNSS measurements.
     * {@code null} means the platform cannot tell (older API levels): in that case the component
     * is reported UNAVAILABLE rather than penalised.
     */
    public void setRawGnssSupported(Boolean supported) {
        rawSupported = supported;
    }

    public void onRawMeasurements(long nowMs, int count) {
        rawSeen = true;
        rawMeasurementCount = count;
        if (rawSupported == null) rawSupported = Boolean.TRUE;
    }

    public boolean isRawSeen() {
        return rawSeen;
    }

    public int rawMeasurementCount() {
        return rawMeasurementCount;
    }

    /** Feeds one radio snapshot: compared with the baseline first, then folded into it. */
    public IntegrityAssessment onSignalUpdate(SignalSample sample) {
        List<AnomalyEvent> events = new ArrayList<>();
        if (sample == null) return buildAssessment(monitoringStartMs, events);

        lastDeviation = signalBaseline.evaluate(sample);
        signalBaseline.add(sample);
        lastSignal = sample;

        IntegrityAssessment assessment = buildAssessment(sample.timeMs, events);

        if (lastDeviation.anomalyCount() >= 2
                && (lastSignalAnomalyEventMs == Long.MIN_VALUE
                    || sample.timeMs - lastSignalAnomalyEventMs > SIGNAL_EVENT_COOLDOWN_MS)) {
            lastSignalAnomalyEventMs = sample.timeMs;
            record(events, sample.timeMs, AnomalyType.SIGNAL_ANOMALY, currentFix,
                    assessment.score, describeDeviation());
        }
        return withEvents(assessment, events);
    }

    /** Feeds one GNSS fix. This is where position and motion consistency are re-evaluated. */
    public IntegrityAssessment onLocation(PositionFix fix) {
        List<AnomalyEvent> events = new ArrayList<>();
        if (fix == null) return buildAssessment(monitoringStartMs, events);

        long now = fix.timeMs;
        MotionState state = motionAnalyzer.stateAt(now);
        lastFixMotionState = state;
        boolean wasLost = gnssLost;

        positionScore = computePositionConsistency(previousFix, fix, state);
        headingPenalty = computeHeadingPenalty(previousFix, fix, state);
        updateSpeedConflict(fix, state);

        previousFix = fix;
        currentFix = fix;
        gnssLost = false;

        IntegrityAssessment assessment = buildAssessment(now, events);

        if (wasLost) {
            record(events, now, AnomalyType.GNSS_RECOVERED, fix, assessment.score,
                    "fix GNSS nuovamente disponibile");
        }

        if (lastPositionPenalty >= POSITION_JUMP_EVENT_PENALTY) {
            AnomalyType type = state == MotionState.STATIONARY
                    ? AnomalyType.GNSS_IMU_CONFLICT
                    : AnomalyType.POSITION_JUMP;
            String why = "spostamento di " + Math.round(lastJumpDistanceM) + " m in "
                    + oneDecimal(lastDtSeconds) + " s ("
                    + oneDecimal(lastImpliedSpeedMps) + " m/s implicita)"
                    + (state == MotionState.STATIONARY ? ", sensori indicano dispositivo fermo" : "");
            record(events, now, type, fix, assessment.score, why);
        }

        if (speedConflictMs >= SPEED_CONFLICT_MS && !speedConflictReported) {
            speedConflictReported = true;
            String why = "velocità GNSS " + oneDecimal(fix.hasSpeed() ? fix.speedMps : 0f)
                    + " m/s con sensori fermi da " + (speedConflictMs / 1000) + " s";
            record(events, now, AnomalyType.SPEED_CONFLICT, fix, assessment.score, why);
        }

        if (isTrustedEligible(assessment.score, assessment.positionConsistency,
                assessment.motionConsistency)) {
            lastTrustedFix = fix;
            lastTrustedTimeMs = fix.timeMs;
        }

        motionAnalyzer.markMagneticReference();
        return withEvents(assessment, events);
    }

    /** Periodic re-evaluation with no new data; also detects loss of the fix. */
    public IntegrityAssessment tick(long nowMs) {
        List<AnomalyEvent> events = new ArrayList<>();
        IntegrityAssessment assessment = buildAssessment(nowMs, events);
        if (!gnssLost && currentFix != null && nowMs - currentFix.timeMs > GNSS_LOST_MS) {
            gnssLost = true;
            record(events, nowMs, AnomalyType.GNSS_LOST, currentFix, assessment.score,
                    "nessun fix GNSS da " + ((nowMs - currentFix.timeMs) / 1000) + " s");
        }
        return withEvents(assessment, events);
    }

    // ------------------------------------------------------------------
    // Scoring
    // ------------------------------------------------------------------

    private IntegrityAssessment buildAssessment(long nowMs, List<AnomalyEvent> events) {
        if (!providerEnabled) {
            List<String> reasons = new ArrayList<>();
            reasons.add("GNSS disattivato sul dispositivo");
            return new IntegrityAssessment(true, 0, IntegrityLevel.UNRELIABLE, reasons,
                    SubScore.unavailable(SIGNAL_QUALITY), SubScore.unavailable(SATELLITE_QUALITY),
                    SubScore.unavailable(POSITION_CONSISTENCY), SubScore.unavailable(MOTION_CONSISTENCY),
                    SubScore.unavailable(RAW_GNSS_AVAILABILITY), MotionState.UNKNOWN, events);
        }

        SubScore signal = computeSignalQuality();
        SubScore satellite = computeSatelliteQuality();
        SubScore position = positionScore;
        SubScore motion = computeMotionConsistency(nowMs);
        SubScore raw = computeRawAvailability(nowMs);

        SubScore[] scores = {signal, satellite, position, motion, raw};
        double[] weights = {W_SIGNAL, W_SATELLITE, W_POSITION, W_MOTION, W_RAW};
        double weighted = weightedScore(scores, weights);

        MotionState state = motionAnalyzer.stateAt(nowMs);

        if (Double.isNaN(weighted)) {
            return new IntegrityAssessment(false, 0, IntegrityLevel.UNRELIABLE,
                    new ArrayList<String>(), signal, satellite, position, motion, raw, state, events);
        }

        int score = applyIntegrityCap((int) Math.round(GeoMath.clamp(weighted, 0.0, 100.0)),
                position, motion, state);
        IntegrityLevel level = IntegrityLevel.forScore(score);
        List<String> reasons = buildReasons(signal, satellite, position, motion, raw);
        return new IntegrityAssessment(true, score, level, reasons,
                signal, satellite, position, motion, raw, state, events);
    }

    /**
     * Weighted mean over the AVAILABLE components only, with the weights renormalised over them.
     * Returns NaN when nothing at all can be measured.
     */
    public static double weightedScore(SubScore[] scores, double[] weights) {
        if (scores == null || weights == null) return Double.NaN;
        double weightSum = 0.0;
        double acc = 0.0;
        int n = Math.min(scores.length, weights.length);
        for (int i = 0; i < n; i++) {
            SubScore s = scores[i];
            if (s != null && s.available) {
                weightSum += weights[i];
                acc += weights[i] * s.value;
            }
        }
        if (weightSum <= 0.0) return Double.NaN;
        return acc / weightSum;
    }

    /**
     * Lowers the weighted score into the band that honestly describes a severe inconsistency.
     * Never raises it. See the cap constants for the rationale.
     */
    public static int applyIntegrityCap(int weightedScore, SubScore position, SubScore motion,
                                        MotionState state) {
        boolean severePosition = position != null && position.available
                && position.value <= SEVERE_POSITION_THRESHOLD;
        boolean severeMotion = motion != null && motion.available
                && motion.value <= SEVERE_MOTION_THRESHOLD;

        int cap = 100;
        if (severePosition && severeMotion) {
            cap = CAP_SEVERE_BOTH;
        } else if (severePosition) {
            cap = state == MotionState.STATIONARY ? CAP_POSITION_VS_STATIONARY : CAP_SEVERE_POSITION;
        } else if (severeMotion) {
            cap = CAP_SEVERE_MOTION;
        }
        return Math.min(weightedScore, cap);
    }

    private SubScore computeSignalQuality() {
        if (lastSignal == null) return SubScore.unavailable(SIGNAL_QUALITY);
        boolean haveCn0 = lastSignal.hasCn0();
        if (!haveCn0 && !lastDeviation.baselineReady) return SubScore.unavailable(SIGNAL_QUALITY);

        double score = 100.0;
        if (haveCn0) {
            if (lastSignal.avgCn0DbHz < CN0_VERY_LOW_DBHZ) score -= SIGNAL_PENALTY_CN0_VERY_LOW;
            else if (lastSignal.avgCn0DbHz < CN0_LOW_DBHZ) score -= SIGNAL_PENALTY_CN0_LOW;
        }
        int deviations = lastDeviation.anomalyCount();
        if (deviations == 1) score -= SIGNAL_PENALTY_ONE_DEVIATION;
        else if (deviations == 2) score -= SIGNAL_PENALTY_TWO_DEVIATIONS;
        else if (deviations >= 3) score -= SIGNAL_PENALTY_THREE_DEVIATIONS;
        return SubScore.of(SIGNAL_QUALITY, score);
    }

    private SubScore computeSatelliteQuality() {
        if (lastSignal == null || !lastSignal.hasSatelliteInfo()) {
            return SubScore.unavailable(SATELLITE_QUALITY);
        }
        int visible = lastSignal.satellitesVisible;
        int used = lastSignal.satellitesUsed;
        int constellations = lastSignal.constellationCount;

        if (visible == 0) return SubScore.of(SATELLITE_QUALITY, 0.0);

        double score = 100.0;
        if (used < SATS_USED_WEAK_FIX) score -= SAT_PENALTY_WEAK_FIX;
        else if (used < SATS_USED_COMFORTABLE) score -= SAT_PENALTY_THIN_FIX;
        if (visible < SATS_VISIBLE_LOW) score -= SAT_PENALTY_FEW_VISIBLE;
        if (constellations <= 1 && visible >= 4) score -= SAT_PENALTY_SINGLE_CONSTELLATION;
        if (constellations >= 3) score += SAT_BONUS_MULTI_CONSTELLATION;
        return SubScore.of(SATELLITE_QUALITY, score);
    }

    /**
     * Position consistency: is the step between two consecutive fixes compatible with the
     * declared uncertainty, the elapsed time and the motion the sensors report?
     * A jump is never proof of anything by itself, so its weight is reduced when the fixes
     * themselves are imprecise.
     */
    SubScore computePositionConsistency(PositionFix previous, PositionFix current, MotionState state) {
        lastPositionPenalty = 0.0;
        lastJumpDistanceM = 0.0;
        lastDtSeconds = 0.0;
        lastImpliedSpeedMps = 0.0;
        if (previous == null || current == null) return SubScore.unavailable(POSITION_CONSISTENCY);

        double dt = Math.max(0.001, (current.timeMs - previous.timeMs) / 1000.0);
        double distance = GeoMath.distanceMeters(previous.latitude, previous.longitude,
                current.latitude, current.longitude);
        double impliedSpeed = distance / dt;

        double accPrev = previous.hasAccuracy() ? previous.accuracyMeters : DEFAULT_ACCURACY_M;
        double accCur = current.hasAccuracy() ? current.accuracyMeters : DEFAULT_ACCURACY_M;
        double sigma = Math.max(MIN_SIGMA_M, accPrev + accCur);

        double plausibleSpeed = plausibleSpeedFor(state, previous, current);
        double expected = plausibleSpeed * dt + sigma;
        double ratio = distance / Math.max(1.0, expected);

        double penalty = jumpPenalty(ratio);

        if (current.hasSpeed()
                && impliedSpeed > Math.max(SPEED_CROSSCHECK_FLOOR_MPS, current.speedMps * 3.0 + 3.0)) {
            penalty += SPEED_CROSSCHECK_PENALTY;
        }

        double worstAccuracy = Math.max(accPrev, accCur);
        double accuracyFactor = GeoMath.clamp(
                1.0 - (worstAccuracy - GOOD_ACCURACY_M) / ACCURACY_DAMP_SPAN_M,
                MIN_ACCURACY_FACTOR, 1.0);
        penalty *= accuracyFactor;

        lastPositionPenalty = penalty;
        lastJumpDistanceM = distance;
        lastDtSeconds = dt;
        lastImpliedSpeedMps = impliedSpeed;
        return SubScore.of(POSITION_CONSISTENCY, 100.0 - penalty);
    }

    static double plausibleSpeedFor(MotionState state, PositionFix previous, PositionFix current) {
        if (state == MotionState.STATIONARY) return STATIONARY_PLAUSIBLE_SPEED_MPS;
        if (state == MotionState.UNKNOWN) return UNKNOWN_PLAUSIBLE_SPEED_MPS;
        double base = 0.0;
        boolean known = false;
        if (previous != null && previous.hasSpeed()) {
            base = Math.max(base, previous.speedMps);
            known = true;
        }
        if (current != null && current.hasSpeed()) {
            base = Math.max(base, current.speedMps);
            known = true;
        }
        if (!known) base = MOVING_DEFAULT_SPEED_MPS;
        return Math.max(MOVING_MIN_PLAUSIBLE_MPS, base * MOVING_SPEED_MARGIN + MOVING_SPEED_OFFSET_MPS);
    }

    /** Monotonic, piecewise-linear mapping from "how many times bigger than plausible" to penalty. */
    static double jumpPenalty(double ratio) {
        if (ratio <= 1.0) return 0.0;
        if (ratio <= 2.0) return (ratio - 1.0) * 30.0;
        if (ratio <= 4.0) return 30.0 + (ratio - 2.0) * 20.0;
        return Math.min(100.0, 70.0 + (ratio - 4.0) * 10.0);
    }

    private void updateSpeedConflict(PositionFix fix, MotionState state) {
        boolean gnssSaysMoving = fix.hasSpeed() && fix.speedMps >= SPEED_MOVING_MPS;
        if (gnssSaysMoving && state == MotionState.STATIONARY) {
            if (speedConflictStartMs == 0L) {
                speedConflictStartMs = fix.timeMs;
                speedConflictMs = 1L;
            } else {
                speedConflictMs = Math.max(1L, fix.timeMs - speedConflictStartMs);
            }
        } else {
            speedConflictStartMs = 0L;
            speedConflictMs = 0L;
            speedConflictReported = false;
        }
    }

    /**
     * Motion consistency. The only penalised situation is a PERSISTENT conflict where the GNSS
     * insists the device is moving while the sensors insist it is not.
     * The opposite case (sensors active, GNSS speed near zero) is normal - a phone handled in a
     * parked car - and is deliberately not penalised.
     */
    private SubScore computeMotionConsistency(long nowMs) {
        if (!motionAnalyzer.isInertialSensorsPresent()) return SubScore.unavailable(MOTION_CONSISTENCY);
        MotionState state = motionAnalyzer.stateAt(nowMs);
        if (state == MotionState.UNKNOWN) return SubScore.unavailable(MOTION_CONSISTENCY);
        if (currentFix == null) return SubScore.unavailable(MOTION_CONSISTENCY);

        double score = 100.0;
        if (speedConflictMs >= SPEED_CONFLICT_SEVERE_MS) score = MOTION_SCORE_CONFLICT_SEVERE;
        else if (speedConflictMs >= SPEED_CONFLICT_MS) score = MOTION_SCORE_CONFLICT;
        else if (speedConflictMs > 0L) score = MOTION_SCORE_CONFLICT_START;

        score -= headingPenalty;
        return SubScore.of(MOTION_CONSISTENCY, score);
    }

    /**
     * Heading is secondary evidence only. Phone orientation is NOT vehicle heading, so this can
     * contribute at most {@link #HEADING_MAX_PENALTY} points and only when the device is clearly
     * moving, both fixes carry a bearing, and the magnetometer says the phone itself was NOT
     * rotated. Turning the phone while standing still can never produce a penalty.
     */
    private double computeHeadingPenalty(PositionFix previous, PositionFix current, MotionState state) {
        if (previous == null || current == null) return 0.0;
        if (state != MotionState.MOVING) return 0.0;
        if (!motionAnalyzer.isMagnetometerPresent()) return 0.0;
        if (!previous.hasSpeed() || !current.hasSpeed()) return 0.0;
        if (previous.speedMps < HEADING_MIN_SPEED_MPS || current.speedMps < HEADING_MIN_SPEED_MPS) return 0.0;
        if (!previous.hasBearing() || !current.hasBearing()) return 0.0;

        double deviceRotation = motionAnalyzer.magneticChangeDegSinceMark();
        if (Double.isNaN(deviceRotation) || deviceRotation > HEADING_MAX_DEVICE_ROTATION_DEG) return 0.0;

        double bearingChange = GeoMath.bearingDeltaDeg(previous.bearingDeg, current.bearingDeg);
        if (bearingChange < HEADING_MIN_BEARING_CHANGE_DEG) return 0.0;

        double span = HEADING_FULL_BEARING_CHANGE_DEG - HEADING_MIN_BEARING_CHANGE_DEG;
        double factor = GeoMath.clamp((bearingChange - HEADING_MIN_BEARING_CHANGE_DEG) / span,
                HEADING_MIN_FACTOR, 1.0);
        return HEADING_MAX_PENALTY * factor;
    }

    /**
     * Raw GNSS availability. A device that simply cannot produce raw measurements is not a
     * suspicious device: in that case, and while the platform cannot tell, the component is
     * UNAVAILABLE rather than scored.
     */
    private SubScore computeRawAvailability(long nowMs) {
        if (rawSeen) return SubScore.of(RAW_GNSS_AVAILABILITY, 100.0);
        if (rawSupported != null && !rawSupported) return SubScore.unavailable(RAW_GNSS_AVAILABILITY);
        if (monitoringStartMs == 0L || nowMs - monitoringStartMs < RAW_GRACE_MS) {
            return SubScore.unavailable(RAW_GNSS_AVAILABILITY);
        }
        if (rawSupported == null) return SubScore.unavailable(RAW_GNSS_AVAILABILITY);
        return SubScore.of(RAW_GNSS_AVAILABILITY, RAW_MISSING_SCORE);
    }

    // ------------------------------------------------------------------
    // Last trusted position
    // ------------------------------------------------------------------

    /**
     * A fix may become the last trusted position only with a high overall score AND no important
     * position or motion inconsistency. Components that are unavailable do not block eligibility.
     */
    public static boolean isTrustedEligible(int score, SubScore position, SubScore motion) {
        if (score < LAST_TRUSTED_MIN_SCORE) return false;
        if (position != null && position.available && position.value < TRUSTED_MIN_COMPONENT) return false;
        if (motion != null && motion.available && motion.value < TRUSTED_MIN_COMPONENT) return false;
        return true;
    }

    // ------------------------------------------------------------------
    // Explanations
    // ------------------------------------------------------------------

    private static final class Reason {
        final int severity;
        final String text;

        Reason(int severity, String text) {
            this.severity = severity;
            this.text = text;
        }
    }

    private List<String> buildReasons(SubScore signal, SubScore satellite, SubScore position,
                                      SubScore motion, SubScore raw) {
        List<Reason> pool = new ArrayList<>();

        if (position.available && position.value < 60.0) {
            String text = lastFixMotionState == MotionState.STATIONARY
                    ? "spostamento GNSS incompatibile con dispositivo fermo"
                    : "posizione poco coerente con il movimento recente";
            pool.add(new Reason((int) (100 - position.value) + 5, text));
        }
        if (motion.available && motion.value < 60.0) {
            pool.add(new Reason((int) (100 - motion.value),
                    "velocità GNSS non coerente con i sensori di movimento"));
        }
        if (signal.available && signal.value < 60.0) {
            String text;
            int deviations = lastDeviation.anomalyCount();
            if (deviations >= 2) text = "variazione simultanea e anomala dei parametri radio";
            else if (lastDeviation.cn0Anomaly) text = "calo improvviso della qualità del segnale";
            else if (lastDeviation.agcAnomaly) text = "variazione anomala del guadagno di ricezione";
            else if (lastDeviation.satellitesAnomaly) text = "perdita improvvisa di satelliti utilizzati";
            else text = "qualità media dei segnali ridotta";
            pool.add(new Reason((int) (100 - signal.value), text));
        }
        if (satellite.available && satellite.value < 60.0 && lastSignal != null) {
            String text;
            if (lastSignal.satellitesUsed < SATS_USED_WEAK_FIX) {
                text = "fix debole: " + lastSignal.satellitesUsed + " satelliti utilizzati";
            } else if (lastSignal.satellitesVisible < SATS_VISIBLE_LOW) {
                text = "pochi satelliti visibili (" + lastSignal.satellitesVisible + ")";
            } else {
                text = "bassa diversità delle costellazioni";
            }
            pool.add(new Reason((int) (100 - satellite.value), text));
        }
        if (raw.available && raw.value < 60.0) {
            pool.add(new Reason(20, "misure GNSS raw non disponibili"));
        }

        if (lastSignal != null && lastSignal.hasSatelliteInfo() && lastSignal.satellitesUsed > 0) {
            pool.add(new Reason(12, lastSignal.satellitesUsed + " satelliti utilizzati"));
        }
        if (lastSignal != null && lastSignal.constellationCount > 0) {
            pool.add(new Reason(11, lastSignal.constellationCount + " costellazioni"));
        }
        if (motion.available && motion.value >= 80.0) {
            pool.add(new Reason(10, "movimento coerente con GNSS"));
        }
        if (signal.available && signal.value >= 80.0) {
            pool.add(new Reason(9, "qualità del segnale stabile"));
        }
        if (position.available && position.value >= 80.0) {
            pool.add(new Reason(8, "posizione coerente con il movimento"));
        }

        Collections.sort(pool, new Comparator<Reason>() {
            @Override
            public int compare(Reason a, Reason b) {
                return Integer.compare(b.severity, a.severity);
            }
        });

        List<String> out = new ArrayList<>();
        for (Reason r : pool) {
            if (out.size() >= MAX_REASONS) break;
            out.add(r.text);
        }
        return out;
    }

    private String describeDeviation() {
        StringBuilder sb = new StringBuilder("variazione simultanea:");
        if (lastDeviation.cn0Anomaly) {
            sb.append(" C/N0 -").append(oneDecimal(lastDeviation.cn0Drop)).append(" dB-Hz");
        }
        if (lastDeviation.satellitesAnomaly) {
            sb.append(" satelliti -").append(Math.round(lastDeviation.satellitesUsedDrop));
        }
        if (lastDeviation.agcAnomaly) {
            sb.append(" AGC ").append(oneDecimal(lastDeviation.agcShift)).append(" dB");
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void record(List<AnomalyEvent> events, long timeMs, AnomalyType type,
                        PositionFix fix, int score, String reason) {
        double lat = fix == null ? Double.NaN : fix.latitude;
        double lon = fix == null ? Double.NaN : fix.longitude;
        float acc = (fix == null || !fix.hasAccuracy()) ? Float.NaN : fix.accuracyMeters;
        AnomalyEvent event = new AnomalyEvent(timeMs, type, lat, lon, acc, score, reason);
        anomalyLog.add(event);
        events.add(event);
    }

    private static IntegrityAssessment withEvents(IntegrityAssessment a, List<AnomalyEvent> events) {
        if (events.isEmpty()) return a;
        return new IntegrityAssessment(a.available, a.score, a.level, a.reasons,
                a.signalQuality, a.satelliteQuality, a.positionConsistency,
                a.motionConsistency, a.rawAvailability, a.motionState, events);
    }

    private static String oneDecimal(double v) {
        if (Double.isNaN(v)) return "--";
        return String.valueOf(Math.round(v * 10.0) / 10.0);
    }
}
