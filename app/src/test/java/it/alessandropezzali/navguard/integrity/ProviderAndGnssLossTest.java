package it.alessandropezzali.navguard.integrity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ProviderAndGnssLossTest {

    private static final double LAT = 45.4640;
    private static final double LON = 9.1900;

    @Test
    public void noDataMeansNoScoreRatherThanZero() {
        IntegrityEngine engine = new IntegrityEngine(new MotionAnalyzer(), new AnomalyLog());
        IntegrityAssessment assessment = engine.tick(1000L);
        assertFalse("senza dati l'assessment non e' disponibile", assessment.available);
        assertTrue(assessment.reasons.isEmpty());
    }

    @Test
    public void aDisabledProviderZeroesTheScore() {
        IntegrityEngine engine = TestSupport.warmEngine(new MotionAnalyzer(), new AnomalyLog(), 1000L);
        engine.setProviderEnabled(false);
        IntegrityAssessment assessment = engine.tick(10_000L);
        assertTrue(assessment.available);
        assertEquals(0, assessment.score);
        assertEquals(IntegrityLevel.UNRELIABLE, assessment.level);
        assertEquals(1, assessment.reasons.size());
        assertTrue(assessment.reasons.get(0).contains("GNSS disattivato"));
    }

    @Test
    public void reEnablingTheProviderRestoresNormalScoring() {
        MotionAnalyzer analyzer = new MotionAnalyzer();
        IntegrityEngine engine = TestSupport.warmEngine(analyzer, new AnomalyLog(), 1000L);
        engine.setProviderEnabled(false);
        assertEquals(0, engine.tick(10_000L).score);

        engine.setProviderEnabled(true);
        TestSupport.quietImu(analyzer, 10_100L, 11_000L);
        IntegrityAssessment recovered = engine.onLocation(TestSupport.fix(LAT, LON, 4f, 0f, 11_000L));
        assertTrue("il motore deve recuperare, score=" + recovered.score, recovered.score >= 80);
        assertEquals(IntegrityLevel.HIGH, recovered.level);
    }

    @Test
    public void gnssLostIsRaisedAfterTheFixGoesStale() {
        MotionAnalyzer analyzer = new MotionAnalyzer();
        AnomalyLog log = new AnomalyLog();
        IntegrityEngine engine = TestSupport.warmEngine(analyzer, log, 1000L);

        TestSupport.quietImu(analyzer, 9000L, 10_000L);
        engine.onLocation(TestSupport.fix(LAT, LON, 4f, 0f, 10_000L));
        assertFalse(engine.isGnssLost());

        IntegrityAssessment stillFine = engine.tick(10_000L + IntegrityEngine.GNSS_LOST_MS - 500L);
        assertFalse(TestSupport.hasEvent(stillFine, AnomalyType.GNSS_LOST));

        IntegrityAssessment lost = engine.tick(10_000L + IntegrityEngine.GNSS_LOST_MS + 1000L);
        assertTrue(TestSupport.hasEvent(lost, AnomalyType.GNSS_LOST));
        assertTrue(engine.isGnssLost());
    }

    @Test
    public void gnssLostIsNotRepeatedOnEveryTick() {
        MotionAnalyzer analyzer = new MotionAnalyzer();
        AnomalyLog log = new AnomalyLog();
        IntegrityEngine engine = TestSupport.warmEngine(analyzer, log, 1000L);

        TestSupport.quietImu(analyzer, 9000L, 10_000L);
        engine.onLocation(TestSupport.fix(LAT, LON, 4f, 0f, 10_000L));

        for (int i = 0; i < 10; i++) {
            engine.tick(25_000L + i * 2000L);
        }
        assertEquals("un solo GNSS_LOST per interruzione",
                1, TestSupport.countEvents(log, AnomalyType.GNSS_LOST));
    }

    @Test
    public void gnssRecoveredIsRaisedOnTheNextFix() {
        MotionAnalyzer analyzer = new MotionAnalyzer();
        AnomalyLog log = new AnomalyLog();
        IntegrityEngine engine = TestSupport.warmEngine(analyzer, log, 1000L);

        TestSupport.quietImu(analyzer, 9000L, 10_000L);
        engine.onLocation(TestSupport.fix(LAT, LON, 4f, 0f, 10_000L));
        engine.tick(30_000L);
        assertTrue(engine.isGnssLost());

        TestSupport.quietImu(analyzer, 31_000L, 32_000L);
        IntegrityAssessment back = engine.onLocation(TestSupport.fix(LAT, LON, 4f, 0f, 32_000L));
        assertTrue(TestSupport.hasEvent(back, AnomalyType.GNSS_RECOVERED));
        assertFalse(engine.isGnssLost());
        assertEquals(1, TestSupport.countEvents(log, AnomalyType.GNSS_RECOVERED));
    }

    @Test
    public void aQuietRunProducesNoEventsAtAll() {
        MotionAnalyzer analyzer = new MotionAnalyzer();
        AnomalyLog log = new AnomalyLog();
        IntegrityEngine engine = TestSupport.warmEngine(analyzer, log, 1000L);

        for (long t = 10_000L; t <= 40_000L; t += 1000L) {
            TestSupport.quietImu(analyzer, t - 900L, t);
            engine.onSignalUpdate(TestSupport.goodSignal(t));
            engine.onLocation(TestSupport.fix(LAT, LON, 4f, 0f, t));
        }
        assertTrue("una sessione normale non deve riempire il registro", log.isEmpty());
    }

    @Test
    public void reasonsNeverExceedThree() {
        MotionAnalyzer analyzer = new MotionAnalyzer();
        IntegrityEngine engine = TestSupport.warmEngine(analyzer, new AnomalyLog(), 1000L);
        TestSupport.quietImu(analyzer, 9000L, 10_000L);
        IntegrityAssessment good = engine.onLocation(TestSupport.fix(LAT, LON, 4f, 0f, 10_000L));
        assertTrue(good.reasons.size() <= IntegrityEngine.MAX_REASONS);

        TestSupport.quietImu(analyzer, 10_100L, 12_000L);
        IntegrityAssessment bad = engine.onLocation(
                TestSupport.fix(LAT + TestSupport.latOffsetForMeters(800.0), LON, 4f, 0f, 12_000L));
        assertTrue(bad.reasons.size() <= IntegrityEngine.MAX_REASONS);
        assertFalse(bad.reasons.isEmpty());
    }

    @Test
    public void aHealthyRunExplainsItselfPositively() {
        MotionAnalyzer analyzer = new MotionAnalyzer();
        IntegrityEngine engine = TestSupport.warmEngine(analyzer, new AnomalyLog(), 1000L);
        TestSupport.quietImu(analyzer, 9000L, 10_000L);
        IntegrityAssessment assessment = engine.onLocation(TestSupport.fix(LAT, LON, 4f, 0f, 10_000L));
        assertEquals(IntegrityLevel.HIGH, assessment.level);
        assertFalse(assessment.reasons.isEmpty());
        assertTrue(assessment.reasons.get(0).contains("satelliti utilizzati"));
    }

    @Test
    public void theAssessmentAlwaysCarriesFiveComponents() {
        IntegrityEngine engine = TestSupport.warmEngine(new MotionAnalyzer(), new AnomalyLog(), 1000L);
        IntegrityAssessment assessment = engine.tick(10_000L);
        assertEquals(5, assessment.subScores().size());
        assertEquals(IntegrityEngine.SIGNAL_QUALITY, assessment.subScores().get(0).name);
        assertEquals(IntegrityEngine.SATELLITE_QUALITY, assessment.subScores().get(1).name);
        assertEquals(IntegrityEngine.POSITION_CONSISTENCY, assessment.subScores().get(2).name);
        assertEquals(IntegrityEngine.MOTION_CONSISTENCY, assessment.subScores().get(3).name);
        assertEquals(IntegrityEngine.RAW_GNSS_AVAILABILITY, assessment.subScores().get(4).name);
    }
}
