package it.alessandropezzali.navguard.integrity;

/**
 * Coarse motion classification derived from the inertial sensors.
 * NAVGUARD deliberately avoids dead reckoning: only these three states are claimed.
 */
public enum MotionState {
    STATIONARY,
    MOVING,
    UNKNOWN
}
