package it.alessandropezzali.navguard.integrity;

/** Kinds of significant event recorded in the local {@link AnomalyLog}. */
public enum AnomalyType {
    POSITION_JUMP,
    GNSS_IMU_CONFLICT,
    SPEED_CONFLICT,
    SIGNAL_ANOMALY,
    GNSS_LOST,
    GNSS_RECOVERED
}
