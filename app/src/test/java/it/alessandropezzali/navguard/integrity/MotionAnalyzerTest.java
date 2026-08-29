package it.alessandropezzali.navguard.integrity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MotionAnalyzerTest {

    @Test
    public void stationaryWhenAccelerationAndGyroStayLow() {
        MotionAnalyzer analyzer = new MotionAnalyzer();
        TestSupport.quietImu(analyzer, 1000L, 3000L);
        assertEquals(MotionState.STATIONARY, analyzer.stateAt(3000L));
    }

    @Test
    public void movingWhenMotionIsSignificantAndPersistent() {
        MotionAnalyzer analyzer = new MotionAnalyzer();
        TestSupport.movingImu(analyzer, 1000L, 3000L);
        assertEquals(MotionState.MOVING, analyzer.stateAt(3000L));
    }

    @Test
    public void unknownWhenNoSensorIsPresent() {
        MotionAnalyzer analyzer = new MotionAnalyzer();
        assertFalse(analyzer.isInertialSensorsPresent());
        assertEquals(MotionState.UNKNOWN, analyzer.stateAt(5000L));
    }

    @Test
    public void unknownWhenTooFewSamples() {
        MotionAnalyzer analyzer = new MotionAnalyzer();
        analyzer.onInertialSample(1000L, 0.05f, 0.01f);
        analyzer.onInertialSample(1100L, 0.05f, 0.01f);
        assertTrue(analyzer.windowSize() < MotionAnalyzer.MIN_SAMPLES);
        assertEquals(MotionState.UNKNOWN, analyzer.stateAt(1100L));
    }

    @Test
    public void unknownWhenSamplesAreStale() {
        MotionAnalyzer analyzer = new MotionAnalyzer();
        TestSupport.quietImu(analyzer, 1000L, 3000L);
        assertEquals(MotionState.STATIONARY, analyzer.stateAt(3000L));
        assertEquals(MotionState.UNKNOWN, analyzer.stateAt(3000L + MotionAnalyzer.STALE_MS + 1000L));
    }

    @Test
    public void singleVibrationDoesNotProduceMoving() {
        MotionAnalyzer analyzer = new MotionAnalyzer();
        TestSupport.quietImu(analyzer, 1000L, 3800L);
        analyzer.onInertialSample(3900L, 6.0f, 1.5f);
        assertFalse(MotionState.MOVING == analyzer.stateAt(3900L));
    }

    @Test
    public void ambiguousActivityStaysUnknownInsteadOfGuessing() {
        MotionAnalyzer analyzer = new MotionAnalyzer();
        // 2 active samples out of 20: above the stationary ceiling, below the moving floor.
        for (int i = 0; i < 20; i++) {
            boolean active = i == 5 || i == 12;
            analyzer.onInertialSample(1000L + i * 100L, active ? 3.0f : 0.05f, 0.01f);
        }
        assertEquals(MotionState.UNKNOWN, analyzer.stateAt(2900L));
    }

    @Test
    public void magnetometerIsOptionalAndNeverRequired() {
        MotionAnalyzer analyzer = new MotionAnalyzer();
        assertFalse(analyzer.isMagnetometerPresent());
        analyzer.markMagneticReference();
        assertTrue(Double.isNaN(analyzer.magneticChangeDegSinceMark()));
        TestSupport.quietImu(analyzer, 1000L, 3000L);
        assertEquals(MotionState.STATIONARY, analyzer.stateAt(3000L));
    }

    @Test
    public void magnetometerReportsHowFarThePhoneWasTurned() {
        MotionAnalyzer analyzer = new MotionAnalyzer();
        analyzer.onMagneticSample(1000L, 30f, 0f, 0f);
        analyzer.markMagneticReference();
        analyzer.onMagneticSample(2000L, 0f, 30f, 0f);
        assertEquals(90.0, analyzer.magneticChangeDegSinceMark(), 0.5);
    }

    /**
     * Turning the phone in the hand while standing still must never be reported as a GNSS
     * problem: no anomaly event, and motion consistency untouched.
     */
    @Test
    public void turningThePhoneWhileStandingStillRaisesNoGnssAnomaly() {
        MotionAnalyzer analyzer = new MotionAnalyzer();
        analyzer.setMagnetometerPresent(true);
        AnomalyLog log = new AnomalyLog();
        IntegrityEngine engine = TestSupport.warmEngine(analyzer, log, 1000L);

        double lat = 45.4640;
        long t = 10_000L;
        IntegrityAssessment assessment = null;
        for (int step = 0; step < 8; step++) {
            TestSupport.rotatingImu(analyzer, t - 900L, t);
            analyzer.onMagneticSample(t, (float) Math.cos(step), (float) Math.sin(step), 0f);
            // The receiver agrees the device is not going anywhere.
            assessment = engine.onLocation(TestSupport.fix(lat, 9.1900, 5f, 0.0f, t));
            t += 1000L;
        }

        assertTrue(log.isEmpty());
        assertTrue(assessment.motionConsistency.available);
        assertEquals(100.0, assessment.motionConsistency.value, 0.001);
        assertTrue(assessment.score >= 80);
    }
}
