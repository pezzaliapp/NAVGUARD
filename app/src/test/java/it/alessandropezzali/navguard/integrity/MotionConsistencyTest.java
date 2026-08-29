package it.alessandropezzali.navguard.integrity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MotionConsistencyTest {

    private static final double LAT = 45.4640;
    private static final double LON = 9.1900;
    private static final float SPEED = 12f;

    /**
     * Drives the engine with fixes that move exactly as fast as the receiver claims, while the
     * inertial sensors report the device is still. Position stays consistent, so only motion
     * consistency is under test.
     */
    private IntegrityAssessment runStationaryConflict(MotionAnalyzer analyzer, AnomalyLog log,
                                                      long durationMs) {
        IntegrityEngine engine = TestSupport.warmEngine(analyzer, log, 1000L);
        IntegrityAssessment last = null;
        long start = 10_000L;
        double lat = LAT;
        for (long t = start; t <= start + durationMs; t += 1000L) {
            TestSupport.quietImu(analyzer, t - 900L, t);
            last = engine.onLocation(TestSupport.fix(lat, LON, 10f, SPEED, t));
            lat += TestSupport.latOffsetForMeters(SPEED);
        }
        return last;
    }

    @Test
    public void coherentGnssAndImuScoreFull() {
        MotionAnalyzer analyzer = new MotionAnalyzer();
        AnomalyLog log = new AnomalyLog();
        IntegrityEngine engine = TestSupport.warmEngine(analyzer, log, 1000L);

        double lat = LAT;
        IntegrityAssessment last = null;
        for (long t = 10_000L; t <= 18_000L; t += 1000L) {
            TestSupport.movingImu(analyzer, t - 900L, t);
            last = engine.onLocation(TestSupport.fix(lat, LON, 10f, SPEED, t));
            lat += TestSupport.latOffsetForMeters(SPEED);
        }

        assertTrue(last.motionConsistency.available);
        assertEquals(100.0, last.motionConsistency.value, 0.001);
        assertFalse(TestSupport.hasEvent(last, AnomalyType.SPEED_CONFLICT));
        assertTrue(last.score >= 80);
    }

    @Test
    public void conflictJustStartedIsFlaggedButNotSevere() {
        MotionAnalyzer analyzer = new MotionAnalyzer();
        IntegrityAssessment first = runStationaryConflict(analyzer, new AnomalyLog(), 0L);
        assertTrue(first.motionConsistency.available);
        assertEquals(IntegrityEngine.MOTION_SCORE_CONFLICT_START,
                first.motionConsistency.value, 0.001);
    }

    @Test
    public void conflictLastingFourSecondsDropsMotionConsistency() {
        MotionAnalyzer analyzer = new MotionAnalyzer();
        IntegrityAssessment last = runStationaryConflict(analyzer, new AnomalyLog(),
                IntegrityEngine.SPEED_CONFLICT_MS);
        assertEquals(IntegrityEngine.MOTION_SCORE_CONFLICT, last.motionConsistency.value, 0.001);
    }

    @Test
    public void conflictLastingEightSecondsZeroesMotionConsistency() {
        MotionAnalyzer analyzer = new MotionAnalyzer();
        IntegrityAssessment last = runStationaryConflict(analyzer, new AnomalyLog(),
                IntegrityEngine.SPEED_CONFLICT_SEVERE_MS);
        assertEquals(IntegrityEngine.MOTION_SCORE_CONFLICT_SEVERE,
                last.motionConsistency.value, 0.001);
        assertTrue("cap atteso, score=" + last.score,
                last.score <= IntegrityEngine.CAP_SEVERE_MOTION);
    }

    @Test
    public void persistentConflictIsLoggedOnceNotOnEveryFix() {
        MotionAnalyzer analyzer = new MotionAnalyzer();
        AnomalyLog log = new AnomalyLog();
        runStationaryConflict(analyzer, log, 12_000L);
        assertEquals(1, TestSupport.countEvents(log, AnomalyType.SPEED_CONFLICT));
    }

    @Test
    public void motionIsUnavailableWithoutInertialSensorsAndCostsNothing() {
        MotionAnalyzer analyzer = new MotionAnalyzer();
        AnomalyLog log = new AnomalyLog();
        IntegrityEngine engine = TestSupport.warmEngine(analyzer, log, 1000L);

        double lat = LAT;
        IntegrityAssessment last = null;
        for (long t = 10_000L; t <= 14_000L; t += 1000L) {
            last = engine.onLocation(TestSupport.fix(lat, LON, 10f, SPEED, t));
            lat += TestSupport.latOffsetForMeters(SPEED);
        }

        assertFalse("senza IMU il componente deve essere UNAVAILABLE",
                last.motionConsistency.available);
        assertTrue("l'assenza dell'IMU non deve abbassare lo score, ottenuto " + last.score,
                last.score >= 80);
        assertTrue(log.isEmpty());
    }

    @Test
    public void motionIsUnavailableWhenTheImuCannotConclude() {
        MotionAnalyzer analyzer = new MotionAnalyzer();
        AnomalyLog log = new AnomalyLog();
        IntegrityEngine engine = TestSupport.warmEngine(analyzer, log, 1000L);

        // Ambiguous activity: neither still enough nor busy enough to decide.
        for (int i = 0; i < 20; i++) {
            boolean active = i == 4 || i == 11;
            analyzer.onInertialSample(9000L + i * 100L, active ? 3.0f : 0.05f, 0.01f);
        }
        assertEquals(MotionState.UNKNOWN, analyzer.stateAt(10_900L));

        IntegrityAssessment last = engine.onLocation(TestSupport.fix(LAT, LON, 10f, SPEED, 10_900L));
        assertFalse(last.motionConsistency.available);
        assertEquals(MotionState.UNKNOWN, last.motionState);
        assertTrue(last.score >= 80);
    }

    /**
     * The opposite situation - sensors busy, receiver reporting no speed - is normal for a phone
     * handled inside a parked car and must never be penalised.
     */
    @Test
    public void movingSensorsWithAStillReceiverAreNotPenalised() {
        MotionAnalyzer analyzer = new MotionAnalyzer();
        AnomalyLog log = new AnomalyLog();
        IntegrityEngine engine = TestSupport.warmEngine(analyzer, log, 1000L);

        IntegrityAssessment last = null;
        for (long t = 10_000L; t <= 20_000L; t += 1000L) {
            TestSupport.movingImu(analyzer, t - 900L, t);
            last = engine.onLocation(TestSupport.fix(LAT, LON, 10f, 0f, t));
        }

        assertEquals(100.0, last.motionConsistency.value, 0.001);
        assertTrue(log.isEmpty());
    }

    @Test
    public void conflictResetsWhenTheReceiverAgreesAgain() {
        MotionAnalyzer analyzer = new MotionAnalyzer();
        AnomalyLog log = new AnomalyLog();
        IntegrityEngine engine = TestSupport.warmEngine(analyzer, log, 1000L);

        double lat = LAT;
        for (long t = 10_000L; t <= 20_000L; t += 1000L) {
            TestSupport.quietImu(analyzer, t - 900L, t);
            engine.onLocation(TestSupport.fix(lat, LON, 10f, SPEED, t));
            lat += TestSupport.latOffsetForMeters(SPEED);
        }

        TestSupport.quietImu(analyzer, 20_100L, 21_000L);
        IntegrityAssessment recovered = engine.onLocation(TestSupport.fix(lat, LON, 10f, 0f, 21_000L));
        assertEquals(100.0, recovered.motionConsistency.value, 0.001);
    }
}
