package it.alessandropezzali.navguard.integrity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RawGnssAvailabilityTest {

    private static final long START = 1000L;

    private IntegrityEngine engine(Boolean supported) {
        IntegrityEngine engine = new IntegrityEngine(new MotionAnalyzer(), new AnomalyLog());
        engine.onMonitoringStarted(START);
        engine.setRawGnssSupported(supported);
        return engine;
    }

    @Test
    public void rawMeasurementsSeenScoreFull() {
        IntegrityEngine engine = engine(Boolean.TRUE);
        engine.onRawMeasurements(START + 2000L, 24);
        IntegrityAssessment assessment = engine.onSignalUpdate(TestSupport.goodSignal(START + 3000L));
        assertTrue(assessment.rawAvailability.available);
        assertEquals(100.0, assessment.rawAvailability.value, 0.001);
        assertTrue(engine.isRawSeen());
        assertEquals(24, engine.rawMeasurementCount());
    }

    @Test
    public void deviceThatCannotProduceRawMeasurementsIsNotPenalised() {
        IntegrityEngine engine = engine(Boolean.FALSE);
        IntegrityAssessment assessment = engine.onSignalUpdate(
                TestSupport.goodSignal(START + IntegrityEngine.RAW_GRACE_MS + 10_000L));
        assertFalse("supporto assente deve dare UNAVAILABLE, non zero",
                assessment.rawAvailability.available);
        assertTrue(assessment.score >= 80);
    }

    @Test
    public void unknownSupportStaysUnavailableInsteadOfGuessing() {
        IntegrityEngine engine = engine(null);
        IntegrityAssessment assessment = engine.onSignalUpdate(
                TestSupport.goodSignal(START + IntegrityEngine.RAW_GRACE_MS + 10_000L));
        assertFalse(assessment.rawAvailability.available);
        assertTrue(assessment.score >= 80);
    }

    @Test
    public void nothingIsJudgedDuringTheGracePeriod() {
        IntegrityEngine engine = engine(Boolean.TRUE);
        IntegrityAssessment early = engine.onSignalUpdate(
                TestSupport.goodSignal(START + IntegrityEngine.RAW_GRACE_MS - 1000L));
        assertFalse("dentro il periodo di grazia il componente e' UNAVAILABLE",
                early.rawAvailability.available);
    }

    @Test
    public void afterTheGracePeriodMissingRawIsScoredNotExcluded() {
        IntegrityEngine engine = engine(Boolean.TRUE);
        IntegrityAssessment late = engine.onSignalUpdate(
                TestSupport.goodSignal(START + IntegrityEngine.RAW_GRACE_MS + 1000L));
        assertTrue(late.rawAvailability.available);
        assertEquals(IntegrityEngine.RAW_MISSING_SCORE, late.rawAvailability.value, 0.001);
    }

    @Test
    public void missingRawComponentDoesNotDragTheScoreDownArtificially() {
        IntegrityEngine unsupported = engine(Boolean.FALSE);
        IntegrityAssessment withoutRaw = unsupported.onSignalUpdate(
                TestSupport.goodSignal(START + 40_000L));

        IntegrityEngine supported = engine(Boolean.TRUE);
        supported.onRawMeasurements(START + 1000L, 24);
        IntegrityAssessment withRaw = supported.onSignalUpdate(TestSupport.goodSignal(START + 40_000L));

        assertEquals("escludere il componente non deve costare punti",
                withRaw.score, withoutRaw.score);
    }

    @Test
    public void receivingRawMeasurementsImpliesSupport() {
        IntegrityEngine engine = engine(null);
        engine.onRawMeasurements(START + 1000L, 12);
        IntegrityAssessment assessment = engine.onSignalUpdate(TestSupport.goodSignal(START + 2000L));
        assertTrue(assessment.rawAvailability.available);
        assertEquals(100.0, assessment.rawAvailability.value, 0.001);
    }
}
