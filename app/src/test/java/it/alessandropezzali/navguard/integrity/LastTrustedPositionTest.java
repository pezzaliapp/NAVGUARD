package it.alessandropezzali.navguard.integrity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LastTrustedPositionTest {

    private static final double LAT = 45.4640;
    private static final double LON = 9.1900;

    @Test
    public void highScoreWithCoherentComponentsIsEligible() {
        assertTrue(IntegrityEngine.isTrustedEligible(
                80, SubScore.of("p", 100), SubScore.of("m", 100)));
        assertTrue(IntegrityEngine.isTrustedEligible(
                95, SubScore.of("p", 70), SubScore.of("m", 70)));
    }

    @Test
    public void scoreBelowEightyIsNotEligible() {
        assertFalse(IntegrityEngine.isTrustedEligible(
                79, SubScore.of("p", 100), SubScore.of("m", 100)));
        assertFalse(IntegrityEngine.isTrustedEligible(
                0, SubScore.of("p", 100), SubScore.of("m", 100)));
    }

    @Test
    public void weakPositionConsistencyBlocksEligibility() {
        assertFalse(IntegrityEngine.isTrustedEligible(
                95, SubScore.of("p", 69), SubScore.of("m", 100)));
    }

    @Test
    public void weakMotionConsistencyBlocksEligibility() {
        assertFalse(IntegrityEngine.isTrustedEligible(
                95, SubScore.of("p", 100), SubScore.of("m", 69)));
    }

    @Test
    public void unavailableComponentsDoNotBlockEligibility() {
        assertTrue(IntegrityEngine.isTrustedEligible(
                90, SubScore.unavailable("p"), SubScore.unavailable("m")));
        assertTrue(IntegrityEngine.isTrustedEligible(
                90, SubScore.of("p", 100), SubScore.unavailable("m")));
        assertTrue(IntegrityEngine.isTrustedEligible(
                90, SubScore.unavailable("p"), SubScore.of("m", 100)));
    }

    @Test
    public void noTrustedFixBeforeAnyLocation() {
        IntegrityEngine engine = new IntegrityEngine(new MotionAnalyzer(), new AnomalyLog());
        assertNull(engine.lastTrustedFix());
        assertEquals(0L, engine.lastTrustedTimeMs());
    }

    @Test
    public void aHealthyFixBecomesTheTrustedOne() {
        MotionAnalyzer analyzer = new MotionAnalyzer();
        IntegrityEngine engine = TestSupport.warmEngine(analyzer, new AnomalyLog(), 1000L);
        TestSupport.quietImu(analyzer, 9000L, 10_000L);
        engine.onLocation(TestSupport.fix(LAT, LON, 4f, 0f, 10_000L));
        TestSupport.quietImu(analyzer, 10_100L, 11_000L);
        IntegrityAssessment assessment = engine.onLocation(TestSupport.fix(LAT, LON, 4f, 0f, 11_000L));

        assertTrue(assessment.score >= IntegrityEngine.LAST_TRUSTED_MIN_SCORE);
        assertNotNull(engine.lastTrustedFix());
        assertEquals(11_000L, engine.lastTrustedTimeMs());
    }

    /** After an anomaly the trusted position must stay where it was. */
    @Test
    public void theTrustedPositionDoesNotAdvanceThroughAnAnomaly() {
        MotionAnalyzer analyzer = new MotionAnalyzer();
        IntegrityEngine engine = TestSupport.warmEngine(analyzer, new AnomalyLog(), 1000L);

        TestSupport.quietImu(analyzer, 9000L, 10_000L);
        engine.onLocation(TestSupport.fix(LAT, LON, 4f, 0f, 10_000L));
        TestSupport.quietImu(analyzer, 10_100L, 11_000L);
        engine.onLocation(TestSupport.fix(LAT, LON, 4f, 0f, 11_000L));
        long trustedBefore = engine.lastTrustedTimeMs();
        double trustedLat = engine.lastTrustedFix().latitude;
        assertEquals(11_000L, trustedBefore);

        TestSupport.quietImu(analyzer, 11_100L, 13_000L);
        IntegrityAssessment jumped = engine.onLocation(
                TestSupport.fix(LAT + TestSupport.latOffsetForMeters(500.0), LON, 4f, 0f, 13_000L));

        assertTrue(jumped.score < IntegrityEngine.LAST_TRUSTED_MIN_SCORE);
        assertEquals("il trusted fix non deve avanzare", trustedBefore, engine.lastTrustedTimeMs());
        assertEquals(trustedLat, engine.lastTrustedFix().latitude, 1e-9);
    }
}
