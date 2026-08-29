package it.alessandropezzali.navguard.integrity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class IntegrityLevelTest {

    @Test
    public void bandBoundariesAreExact() {
        assertEquals(IntegrityLevel.HIGH, IntegrityLevel.forScore(100));
        assertEquals(IntegrityLevel.HIGH, IntegrityLevel.forScore(80));

        assertEquals(IntegrityLevel.NORMAL, IntegrityLevel.forScore(79));
        assertEquals(IntegrityLevel.NORMAL, IntegrityLevel.forScore(60));

        assertEquals(IntegrityLevel.ATTENTION, IntegrityLevel.forScore(59));
        assertEquals(IntegrityLevel.ATTENTION, IntegrityLevel.forScore(40));

        assertEquals(IntegrityLevel.ANOMALY, IntegrityLevel.forScore(39));
        assertEquals(IntegrityLevel.ANOMALY, IntegrityLevel.forScore(20));

        assertEquals(IntegrityLevel.UNRELIABLE, IntegrityLevel.forScore(19));
        assertEquals(IntegrityLevel.UNRELIABLE, IntegrityLevel.forScore(0));
    }

    @Test
    public void labelsAreTheAgreedWording() {
        assertEquals("AFFIDABILITÀ ALTA", IntegrityLevel.HIGH.label);
        assertEquals("NORMALE", IntegrityLevel.NORMAL.label);
        assertEquals("ATTENZIONE", IntegrityLevel.ATTENTION.label);
        assertEquals("ANOMALIA", IntegrityLevel.ANOMALY.label);
        assertEquals("GNSS NON AFFIDABILE", IntegrityLevel.UNRELIABLE.label);
    }

    @Test
    public void noLabelClaimsSpoofingOrJamming() {
        for (IntegrityLevel level : IntegrityLevel.values()) {
            String label = level.label.toLowerCase();
            assertFalse(label.contains("spoof"));
            assertFalse(label.contains("jamming"));
            assertFalse(label.contains("interferenza"));
            assertFalse(label.contains("sicuro"));
            assertFalse(label.contains("safe"));
        }
    }

    @Test
    public void everyScoreInRangeMapsToALevel() {
        for (int score = 0; score <= 100; score++) {
            assertFalse(IntegrityLevel.forScore(score) == null);
        }
    }
}
