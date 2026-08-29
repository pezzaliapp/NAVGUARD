package it.alessandropezzali.navguard.integrity;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Coarse motion classifier fed with inertial sensor magnitudes.
 *
 * It deliberately does NOT integrate acceleration: the drift of a phone-grade IMU makes any
 * dead-reckoned position useless. The only question asked here is whether the device looks
 * STATIONARY, MOVING, or whether the sensors do not allow a conclusion (UNKNOWN).
 *
 * A sample counts as "active" when the linear acceleration or the gyroscope rate exceeds the
 * thresholds inherited from v0.2.1. Classification then works on the ratio of active samples
 * inside a rolling window, so a single vibration cannot produce MOVING.
 */
public final class MotionAnalyzer {
    /** Linear acceleration magnitude above which a sample is considered active (m/s^2). */
    public static final float LINEAR_ACC_THRESHOLD_MPS2 = 0.7f;
    /** Gyroscope rate magnitude above which a sample is considered active (rad/s). */
    public static final float GYRO_THRESHOLD_RADS = 0.12f;
    /** Rolling window used for the active-sample ratio. */
    public static final long WINDOW_MS = 3000L;
    /** No inertial sample for this long means the classifier can no longer conclude. */
    public static final long STALE_MS = 5000L;
    /** Below this many samples in the window nothing is claimed. */
    public static final int MIN_SAMPLES = 4;
    /** Active ratio at or above this value means MOVING. */
    public static final double MOVING_ACTIVE_RATIO = 0.25;
    /** Active ratio at or below this value means STATIONARY; in between the state is UNKNOWN. */
    public static final double STATIONARY_ACTIVE_RATIO = 0.05;

    private static final class Sample {
        final long timeMs;
        final boolean active;

        Sample(long timeMs, boolean active) {
            this.timeMs = timeMs;
            this.active = active;
        }
    }

    private final Deque<Sample> samples = new ArrayDeque<>();
    private boolean inertialSensorsPresent = false;
    private boolean magnetometerPresent = false;
    private long lastSampleMs = 0L;
    private float lastLinearAcc = 0f;
    private float lastGyro = 0f;

    private float[] lastMagnetic;
    private float[] markedMagnetic;

    public void setInertialSensorsPresent(boolean present) {
        inertialSensorsPresent = present;
    }

    public void setMagnetometerPresent(boolean present) {
        magnetometerPresent = present;
    }

    public boolean isInertialSensorsPresent() {
        return inertialSensorsPresent;
    }

    public boolean isMagnetometerPresent() {
        return magnetometerPresent;
    }

    public float lastLinearAcceleration() {
        return lastLinearAcc;
    }

    public float lastGyroRate() {
        return lastGyro;
    }

    /** Feeds one inertial sample. Either magnitude may be NaN when that sensor is missing. */
    public void onInertialSample(long timeMs, float linearAccMagnitude, float gyroMagnitude) {
        inertialSensorsPresent = true;
        if (!Float.isNaN(linearAccMagnitude)) lastLinearAcc = linearAccMagnitude;
        if (!Float.isNaN(gyroMagnitude)) lastGyro = gyroMagnitude;
        boolean active = (!Float.isNaN(linearAccMagnitude) && linearAccMagnitude > LINEAR_ACC_THRESHOLD_MPS2)
                || (!Float.isNaN(gyroMagnitude) && gyroMagnitude > GYRO_THRESHOLD_RADS);
        samples.addLast(new Sample(timeMs, active));
        lastSampleMs = timeMs;
        prune(timeMs);
    }

    /** Magnetometer is a secondary indicator only: it never raises an anomaly by itself. */
    public void onMagneticSample(long timeMs, float x, float y, float z) {
        magnetometerPresent = true;
        lastMagnetic = new float[]{x, y, z};
    }

    /** Records the current magnetic vector as the reference for the next heading comparison. */
    public void markMagneticReference() {
        markedMagnetic = lastMagnetic == null ? null : lastMagnetic.clone();
    }

    /**
     * Angle between the magnetic vector at the last mark and the current one, in degrees.
     * NaN when the magnetometer is missing or no reference has been taken yet.
     * A large value means the phone itself was rotated, which is not a GNSS anomaly.
     */
    public double magneticChangeDegSinceMark() {
        if (!magnetometerPresent) return Double.NaN;
        return GeoMath.angleBetweenDeg(markedMagnetic, lastMagnetic);
    }

    private void prune(long nowMs) {
        while (!samples.isEmpty() && nowMs - samples.peekFirst().timeMs > WINDOW_MS) {
            samples.removeFirst();
        }
    }

    /** Number of samples currently inside the rolling window. */
    public int windowSize() {
        return samples.size();
    }

    public MotionState stateAt(long nowMs) {
        if (!inertialSensorsPresent) return MotionState.UNKNOWN;
        prune(nowMs);
        if (samples.size() < MIN_SAMPLES) return MotionState.UNKNOWN;
        if (nowMs - lastSampleMs > STALE_MS) return MotionState.UNKNOWN;

        int active = 0;
        for (Sample s : samples) {
            if (s.active) active++;
        }
        double ratio = (double) active / (double) samples.size();
        if (ratio >= MOVING_ACTIVE_RATIO) return MotionState.MOVING;
        if (ratio <= STATIONARY_ACTIVE_RATIO) return MotionState.STATIONARY;
        return MotionState.UNKNOWN;
    }

    public void reset() {
        samples.clear();
        lastSampleMs = 0L;
        markedMagnetic = null;
    }
}
