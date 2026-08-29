package it.alessandropezzali.navguard.integrity;

/**
 * User-facing bands of the GNSS Integrity Score.
 * The wording deliberately never claims spoofing or jamming: NAVGUARD reports observed
 * inconsistency, not a cause.
 */
public enum IntegrityLevel {
    HIGH("AFFIDABILITÀ ALTA", 80),
    NORMAL("NORMALE", 60),
    ATTENTION("ATTENZIONE", 40),
    ANOMALY("ANOMALIA", 20),
    UNRELIABLE("GNSS NON AFFIDABILE", 0);

    public final String label;
    public final int minScore;

    IntegrityLevel(String label, int minScore) {
        this.label = label;
        this.minScore = minScore;
    }

    public static IntegrityLevel forScore(int score) {
        if (score >= HIGH.minScore) return HIGH;
        if (score >= NORMAL.minScore) return NORMAL;
        if (score >= ATTENTION.minScore) return ATTENTION;
        if (score >= ANOMALY.minScore) return ANOMALY;
        return UNRELIABLE;
    }
}
