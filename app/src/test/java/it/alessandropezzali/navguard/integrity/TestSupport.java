package it.alessandropezzali.navguard.integrity;

/**
 * Shared fixtures for the integrity tests. Everything here is plain Java: no Android, no GNSS.
 */
final class TestSupport {

    private TestSupport() {
    }

    /** Feeds inertial samples every 100 ms with the device essentially still. */
    static void quietImu(MotionAnalyzer analyzer, long fromMs, long toMs) {
        for (long t = fromMs; t <= toMs; t += 100L) {
            analyzer.onInertialSample(t, 0.05f, 0.01f);
        }
    }

    /** Feeds inertial samples every 100 ms with the device clearly moving. */
    static void movingImu(MotionAnalyzer analyzer, long fromMs, long toMs) {
        for (long t = fromMs; t <= toMs; t += 100L) {
            analyzer.onInertialSample(t, 3.0f, 0.6f);
        }
    }

    /** Feeds samples where only the gyroscope is active: the phone is being turned in the hand. */
    static void rotatingImu(MotionAnalyzer analyzer, long fromMs, long toMs) {
        for (long t = fromMs; t <= toMs; t += 100L) {
            analyzer.onInertialSample(t, 0.05f, 1.2f);
        }
    }

    static PositionFix fix(double lat, double lon, float accuracy, float speed, long timeMs) {
        return new PositionFix(lat, lon, accuracy, speed, Float.NaN, timeMs);
    }

    static PositionFix fixWithBearing(double lat, double lon, float accuracy, float speed,
                                      float bearing, long timeMs) {
        return new PositionFix(lat, lon, accuracy, speed, bearing, timeMs);
    }

    static SignalSample goodSignal(long timeMs) {
        return new SignalSample(timeMs, 40.0, 22, 16, 4, 60.0);
    }

    /**
     * An engine warmed up into a healthy state: raw measurements seen, a ready signal baseline
     * and a comfortable satellite picture. Signal, satellite and raw all sit at 100.
     */
    static IntegrityEngine warmEngine(MotionAnalyzer analyzer, AnomalyLog log, long startMs) {
        IntegrityEngine engine = new IntegrityEngine(analyzer, log);
        engine.onMonitoringStarted(startMs);
        engine.setRawGnssSupported(Boolean.TRUE);
        engine.onRawMeasurements(startMs, 24);
        for (int i = 0; i < 8; i++) {
            engine.onSignalUpdate(goodSignal(startMs + i * 1000L));
        }
        return engine;
    }

    static boolean hasEvent(IntegrityAssessment assessment, AnomalyType type) {
        for (AnomalyEvent event : assessment.newEvents) {
            if (event.type == type) return true;
        }
        return false;
    }

    static int countEvents(AnomalyLog log, AnomalyType type) {
        int n = 0;
        for (AnomalyEvent event : log.all()) {
            if (event.type == type) n++;
        }
        return n;
    }

    /** Metres of latitude, near enough for test fixtures. */
    static double latOffsetForMeters(double meters) {
        return meters / 111_320.0;
    }
}
