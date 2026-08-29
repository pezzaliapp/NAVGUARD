package it.alessandropezzali.navguard.integrity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PositionConsistencyTest {

    private static final double LAT = 45.4640;
    private static final double LON = 9.1900;

    private IntegrityEngine engine() {
        return new IntegrityEngine(new MotionAnalyzer(), new AnomalyLog());
    }

    @Test
    public void unavailableWithoutAPreviousFix() {
        SubScore score = engine().computePositionConsistency(
                null, TestSupport.fix(LAT, LON, 5f, 0f, 1000L), MotionState.UNKNOWN);
        assertFalse(score.available);
    }

    @Test
    public void normalWalkingStepIsFullyConsistent() {
        PositionFix a = TestSupport.fix(LAT, LON, 5f, 1.4f, 10_000L);
        PositionFix b = TestSupport.fix(LAT + TestSupport.latOffsetForMeters(1.4), LON,
                5f, 1.4f, 11_000L);
        SubScore score = engine().computePositionConsistency(a, b, MotionState.MOVING);
        assertTrue(score.available);
        assertEquals(100.0, score.value, 0.001);
    }

    @Test
    public void drivingStepIsConsistentWhenSpeedMatches() {
        PositionFix a = TestSupport.fix(LAT, LON, 8f, 25f, 10_000L);
        PositionFix b = TestSupport.fix(LAT + TestSupport.latOffsetForMeters(25.0), LON,
                8f, 25f, 11_000L);
        SubScore score = engine().computePositionConsistency(a, b, MotionState.MOVING);
        assertEquals(100.0, score.value, 0.001);
    }

    @Test
    public void largeJumpWithGoodAccuracyIsHeavilyPenalised() {
        PositionFix a = TestSupport.fix(LAT, LON, 5f, 0f, 10_000L);
        PositionFix b = TestSupport.fix(LAT + TestSupport.latOffsetForMeters(500.0), LON,
                5f, 0f, 11_000L);
        SubScore score = engine().computePositionConsistency(a, b, MotionState.STATIONARY);
        assertTrue(score.available);
        assertTrue("atteso crollo, ottenuto " + score.value, score.value <= 5.0);
    }

    @Test
    public void theSameJumpWithPoorAccuracyWeighsMuchLess() {
        PositionFix goodA = TestSupport.fix(LAT, LON, 5f, 0f, 10_000L);
        PositionFix goodB = TestSupport.fix(LAT + TestSupport.latOffsetForMeters(500.0), LON,
                5f, 0f, 11_000L);
        PositionFix poorA = TestSupport.fix(LAT, LON, 120f, 0f, 10_000L);
        PositionFix poorB = TestSupport.fix(LAT + TestSupport.latOffsetForMeters(500.0), LON,
                120f, 0f, 11_000L);

        double good = engine().computePositionConsistency(goodA, goodB, MotionState.STATIONARY).value;
        double poor = engine().computePositionConsistency(poorA, poorB, MotionState.STATIONARY).value;

        assertTrue("accuracy scarsa deve pesare meno: buona=" + good + " scarsa=" + poor,
                poor > good + 40.0);
    }

    @Test
    public void anInconclusiveImuMakesTheEngineMorePermissive() {
        PositionFix a = TestSupport.fix(LAT, LON, 5f, 0f, 10_000L);
        PositionFix b = TestSupport.fix(LAT + TestSupport.latOffsetForMeters(40.0), LON,
                5f, 0f, 11_000L);
        double stationary = engine().computePositionConsistency(a, b, MotionState.STATIONARY).value;
        double unknown = engine().computePositionConsistency(a, b, MotionState.UNKNOWN).value;
        assertTrue("unknown=" + unknown + " stationary=" + stationary, unknown > stationary);
    }

    @Test
    public void jumpPenaltyGrowsMonotonicallyWithTheRatio() {
        assertEquals(0.0, IntegrityEngine.jumpPenalty(0.5), 0.001);
        assertEquals(0.0, IntegrityEngine.jumpPenalty(1.0), 0.001);
        double previous = -1.0;
        for (double ratio = 1.0; ratio <= 12.0; ratio += 0.25) {
            double penalty = IntegrityEngine.jumpPenalty(ratio);
            assertTrue("non monotona a ratio=" + ratio, penalty >= previous);
            assertTrue(penalty <= 100.0);
            previous = penalty;
        }
    }

    @Test
    public void plausibleSpeedDependsOnTheMotionState() {
        PositionFix a = TestSupport.fix(LAT, LON, 5f, 20f, 10_000L);
        PositionFix b = TestSupport.fix(LAT, LON, 5f, 20f, 11_000L);
        assertEquals(IntegrityEngine.STATIONARY_PLAUSIBLE_SPEED_MPS,
                IntegrityEngine.plausibleSpeedFor(MotionState.STATIONARY, a, b), 0.001);
        assertEquals(IntegrityEngine.UNKNOWN_PLAUSIBLE_SPEED_MPS,
                IntegrityEngine.plausibleSpeedFor(MotionState.UNKNOWN, a, b), 0.001);
        assertTrue(IntegrityEngine.plausibleSpeedFor(MotionState.MOVING, a, b) > 20.0);
    }

    /**
     * The whole point of the cap: a big jump while the sensors insist the device is still must
     * not be reported as NORMALE just because position is only 30% of the weighted mean.
     */
    @Test
    public void bigJumpWhileStationaryDoesNotStayNormal() {
        MotionAnalyzer analyzer = new MotionAnalyzer();
        AnomalyLog log = new AnomalyLog();
        IntegrityEngine engine = TestSupport.warmEngine(analyzer, log, 1000L);

        TestSupport.quietImu(analyzer, 9000L, 10_000L);
        engine.onLocation(TestSupport.fix(LAT, LON, 4f, 0f, 10_000L));

        TestSupport.quietImu(analyzer, 10_100L, 12_000L);
        IntegrityAssessment jumped = engine.onLocation(
                TestSupport.fix(LAT + TestSupport.latOffsetForMeters(500.0), LON, 4f, 0f, 12_000L));

        assertTrue(jumped.available);
        assertTrue("position score atteso al minimo, ottenuto " + jumped.positionConsistency.value,
                jumped.positionConsistency.value <= IntegrityEngine.SEVERE_POSITION_THRESHOLD);
        assertFalse("non deve restare NORMALE", jumped.level == IntegrityLevel.NORMAL);
        assertFalse(jumped.level == IntegrityLevel.HIGH);
        assertTrue("atteso cap in banda ANOMALIA, score=" + jumped.score,
                jumped.score <= IntegrityEngine.CAP_POSITION_VS_STATIONARY);
        assertEquals(IntegrityLevel.ANOMALY, jumped.level);
        assertTrue(TestSupport.hasEvent(jumped, AnomalyType.GNSS_IMU_CONFLICT));
    }

    @Test
    public void capNeverRaisesTheScore() {
        assertEquals(90, IntegrityEngine.applyIntegrityCap(
                90, SubScore.of("p", 100), SubScore.of("m", 100), MotionState.MOVING));
        assertEquals(10, IntegrityEngine.applyIntegrityCap(
                10, SubScore.of("p", 0), SubScore.of("m", 0), MotionState.STATIONARY));
    }

    @Test
    public void jumpWhileMovingIsLoggedAsPositionJumpNotImuConflict() {
        MotionAnalyzer analyzer = new MotionAnalyzer();
        AnomalyLog log = new AnomalyLog();
        IntegrityEngine engine = TestSupport.warmEngine(analyzer, log, 1000L);

        TestSupport.movingImu(analyzer, 9000L, 10_000L);
        engine.onLocation(TestSupport.fix(LAT, LON, 4f, 5f, 10_000L));

        TestSupport.movingImu(analyzer, 10_100L, 11_000L);
        IntegrityAssessment jumped = engine.onLocation(
                TestSupport.fix(LAT + TestSupport.latOffsetForMeters(2000.0), LON, 4f, 5f, 11_000L));

        assertTrue(TestSupport.hasEvent(jumped, AnomalyType.POSITION_JUMP));
        assertFalse(TestSupport.hasEvent(jumped, AnomalyType.GNSS_IMU_CONFLICT));
    }
}
