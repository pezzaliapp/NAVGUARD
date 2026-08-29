package it.alessandropezzali.navguard.integrity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SignalBaselineTest {

    private SignalBaseline stableBaseline() {
        SignalBaseline baseline = new SignalBaseline();
        for (int i = 0; i < 10; i++) {
            baseline.add(new SignalSample(1000L + i * 1000L, 38.0, 20, 14, 4, 60.0));
        }
        return baseline;
    }

    @Test
    public void aStableBaselineRaisesNoAnomaly() {
        SignalBaseline baseline = stableBaseline();
        assertTrue(baseline.isReady());
        SignalDeviation deviation = baseline.evaluate(
                new SignalSample(12_000L, 37.4, 20, 14, 4, 60.3));
        assertTrue(deviation.baselineReady);
        assertEquals(0, deviation.anomalyCount());
    }

    @Test
    public void tooFewSamplesProduceNoFalseAnomaly() {
        SignalBaseline baseline = new SignalBaseline();
        for (int i = 0; i < SignalBaseline.MIN_SAMPLES_FOR_BASELINE - 1; i++) {
            baseline.add(new SignalSample(1000L + i * 1000L, 38.0, 20, 14, 4, 60.0));
        }
        assertFalse(baseline.isReady());
        SignalDeviation deviation = baseline.evaluate(new SignalSample(9000L, 5.0, 1, 0, 1, 95.0));
        assertFalse(deviation.baselineReady);
        assertEquals(0, deviation.anomalyCount());
    }

    @Test
    public void aSignificantCn0DropIsFlagged() {
        SignalDeviation deviation = stableBaseline().evaluate(
                new SignalSample(12_000L, 38.0 - SignalBaseline.CN0_DROP_DB - 1.0, 20, 14, 4, 60.0));
        assertTrue(deviation.cn0Anomaly);
        assertFalse(deviation.satellitesAnomaly);
        assertFalse(deviation.agcAnomaly);
        assertEquals(1, deviation.anomalyCount());
    }

    @Test
    public void aSignificantSatelliteDropIsFlagged() {
        SignalDeviation deviation = stableBaseline().evaluate(
                new SignalSample(12_000L, 38.0, 8, 5, 4, 60.0));
        assertTrue(deviation.satellitesAnomaly);
        assertEquals(1, deviation.anomalyCount());
    }

    @Test
    public void aSmallSatelliteDropIsNotEnough() {
        SignalDeviation deviation = stableBaseline().evaluate(
                new SignalSample(12_000L, 38.0, 18, 12, 4, 60.0));
        assertFalse(deviation.satellitesAnomaly);
    }

    @Test
    public void anAgcShiftIsFlaggedInEitherDirection() {
        SignalDeviation up = stableBaseline().evaluate(
                new SignalSample(12_000L, 38.0, 20, 14, 4, 60.0 + SignalBaseline.AGC_SHIFT_DB + 2.0));
        SignalDeviation down = stableBaseline().evaluate(
                new SignalSample(12_000L, 38.0, 20, 14, 4, 60.0 - SignalBaseline.AGC_SHIFT_DB - 2.0));
        assertTrue(up.agcAnomaly);
        assertTrue(down.agcAnomaly);
    }

    @Test
    public void missingAgcCostsNothing() {
        SignalDeviation deviation = stableBaseline().evaluate(
                new SignalSample(12_000L, 38.0, 20, 14, 4, Double.NaN));
        assertFalse(deviation.agcAnomaly);
        assertEquals(0, deviation.anomalyCount());
        assertTrue(Double.isNaN(deviation.agcShift));
    }

    @Test
    public void simultaneousDeviationsAreCountedTogether() {
        SignalDeviation deviation = stableBaseline().evaluate(
                new SignalSample(12_000L, 24.0, 7, 5, 2, 72.0));
        assertEquals(3, deviation.anomalyCount());
    }

    /** The candidate must be judged against the baseline BEFORE it becomes part of it. */
    @Test
    public void theCandidateIsEvaluatedBeforeBeingAdded() {
        SignalBaseline baseline = stableBaseline();
        assertEquals(38.0, baseline.baselineCn0(), 0.001);

        SignalSample degraded = new SignalSample(12_000L, 24.0, 20, 14, 4, 60.0);
        SignalDeviation first = baseline.evaluate(degraded);
        assertEquals(38.0, baseline.baselineCn0(), 0.001);
        assertTrue(first.cn0Anomaly);

        baseline.add(degraded);
        assertTrue("dopo l'inserimento la baseline deve muoversi", baseline.baselineCn0() < 38.0);

        SignalDeviation second = baseline.evaluate(degraded);
        assertTrue("la seconda valutazione deve vedere uno scostamento minore",
                second.cn0Drop < first.cn0Drop);
    }

    @Test
    public void theWindowIsBounded() {
        SignalBaseline baseline = new SignalBaseline();
        for (int i = 0; i < SignalBaseline.MAX_SAMPLES + 40; i++) {
            baseline.add(new SignalSample(1000L + i * 100L, 38.0, 20, 14, 4, 60.0));
        }
        assertTrue(baseline.size() <= SignalBaseline.MAX_SAMPLES);
    }

    @Test
    public void samplesOlderThanTheWindowAreDropped() {
        SignalBaseline baseline = stableBaseline();
        assertTrue(baseline.size() > 0);
        baseline.add(new SignalSample(1000L + SignalBaseline.WINDOW_MS + 60_000L,
                38.0, 20, 14, 4, 60.0));
        assertEquals(1, baseline.size());
        assertFalse(baseline.isReady());
    }

    /** An isolated deviation must weigh less than several at once. */
    @Test
    public void oneDeviationCostsLessThanThreeInTheEngine() {
        IntegrityEngine single = new IntegrityEngine(new MotionAnalyzer(), new AnomalyLog());
        IntegrityEngine multiple = new IntegrityEngine(new MotionAnalyzer(), new AnomalyLog());
        single.onMonitoringStarted(1000L);
        multiple.onMonitoringStarted(1000L);
        for (int i = 0; i < 8; i++) {
            SignalSample s = new SignalSample(1000L + i * 1000L, 38.0, 20, 14, 4, 60.0);
            single.onSignalUpdate(s);
            multiple.onSignalUpdate(s);
        }
        IntegrityAssessment one = single.onSignalUpdate(
                new SignalSample(10_000L, 30.0, 20, 14, 4, 60.0));
        IntegrityAssessment three = multiple.onSignalUpdate(
                new SignalSample(10_000L, 24.0, 7, 5, 2, 72.0));
        assertTrue("una sola deviazione deve pesare meno di tre",
                one.signalQuality.value > three.signalQuality.value);
    }

    @Test
    public void twoSimultaneousDeviationsRaiseASignalAnomalyEvent() {
        AnomalyLog log = new AnomalyLog();
        IntegrityEngine engine = new IntegrityEngine(new MotionAnalyzer(), log);
        engine.onMonitoringStarted(1000L);
        for (int i = 0; i < 8; i++) {
            engine.onSignalUpdate(new SignalSample(1000L + i * 1000L, 38.0, 20, 14, 4, 60.0));
        }
        IntegrityAssessment assessment = engine.onSignalUpdate(
                new SignalSample(10_000L, 24.0, 7, 5, 2, 72.0));
        assertTrue(TestSupport.hasEvent(assessment, AnomalyType.SIGNAL_ANOMALY));
        assertEquals(1, TestSupport.countEvents(log, AnomalyType.SIGNAL_ANOMALY));
    }

    @Test
    public void signalAnomalyEventsAreRateLimited() {
        AnomalyLog log = new AnomalyLog();
        IntegrityEngine engine = new IntegrityEngine(new MotionAnalyzer(), log);
        engine.onMonitoringStarted(1000L);
        for (int i = 0; i < 8; i++) {
            engine.onSignalUpdate(new SignalSample(1000L + i * 1000L, 38.0, 20, 14, 4, 60.0));
        }
        for (int i = 0; i < 5; i++) {
            engine.onSignalUpdate(new SignalSample(10_000L + i * 1000L, 24.0, 7, 5, 2, 72.0));
        }
        assertEquals("un solo evento entro il cooldown",
                1, TestSupport.countEvents(log, AnomalyType.SIGNAL_ANOMALY));
    }
}
