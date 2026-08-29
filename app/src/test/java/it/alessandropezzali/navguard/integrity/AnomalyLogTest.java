package it.alessandropezzali.navguard.integrity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class AnomalyLogTest {

    private AnomalyEvent event(int index) {
        return new AnomalyEvent(index, AnomalyType.SIGNAL_ANOMALY, 45.0, 9.0, 5f, 50, "e" + index);
    }

    private AnomalyLog filled(int count) {
        AnomalyLog log = new AnomalyLog();
        for (int i = 0; i < count; i++) log.add(event(i));
        return log;
    }

    @Test
    public void aFreshLogIsEmpty() {
        AnomalyLog log = new AnomalyLog();
        assertTrue(log.isEmpty());
        assertEquals(0, log.size());
        assertTrue(log.recent(10).isEmpty());
    }

    @Test
    public void theLogNeverExceedsOneHundredEvents() {
        assertEquals(100, AnomalyLog.MAX_EVENTS);
        AnomalyLog log = filled(250);
        assertEquals(100, log.size());
    }

    @Test
    public void theOldestEventIsDroppedFirst() {
        AnomalyLog log = filled(101);
        assertEquals(100, log.size());
        assertEquals("e1", log.oldest().reason);
        assertEquals("e100", log.newest().reason);
    }

    @Test
    public void recentReturnsAtMostTheRequestedNumber() {
        AnomalyLog log = filled(50);
        assertEquals(10, log.recent(10).size());
        assertEquals(3, log.recent(3).size());
        assertEquals(50, log.recent(500).size());
    }

    @Test
    public void recentIsOrderedFromNewestToOldest() {
        AnomalyLog log = filled(20);
        List<AnomalyEvent> recent = log.recent(5);
        assertEquals("e19", recent.get(0).reason);
        assertEquals("e18", recent.get(1).reason);
        assertEquals("e15", recent.get(4).reason);
    }

    @Test
    public void clearEmptiesTheLog() {
        AnomalyLog log = filled(30);
        log.clear();
        assertTrue(log.isEmpty());
        assertEquals(0, log.size());
        assertTrue(log.recent(10).isEmpty());
    }

    @Test
    public void nullEventsAreIgnored() {
        AnomalyLog log = new AnomalyLog();
        log.add(null);
        assertTrue(log.isEmpty());
    }

    @Test
    public void eventsCarryTheirContext() {
        AnomalyEvent e = new AnomalyEvent(1234L, AnomalyType.POSITION_JUMP,
                45.4640, 9.1900, 7f, 42, "motivo");
        assertTrue(e.hasPosition());
        assertTrue(e.hasAccuracy());
        assertEquals(42, e.integrityScore);
        assertEquals(AnomalyType.POSITION_JUMP, e.type);

        AnomalyEvent blind = new AnomalyEvent(1234L, AnomalyType.GNSS_LOST,
                Double.NaN, Double.NaN, Float.NaN, 0, "nessun fix");
        assertFalse(blind.hasPosition());
        assertFalse(blind.hasAccuracy());
    }

    @Test
    public void allSixEventTypesExist() {
        assertEquals(6, AnomalyType.values().length);
        assertEquals(AnomalyType.POSITION_JUMP, AnomalyType.valueOf("POSITION_JUMP"));
        assertEquals(AnomalyType.GNSS_IMU_CONFLICT, AnomalyType.valueOf("GNSS_IMU_CONFLICT"));
        assertEquals(AnomalyType.SPEED_CONFLICT, AnomalyType.valueOf("SPEED_CONFLICT"));
        assertEquals(AnomalyType.SIGNAL_ANOMALY, AnomalyType.valueOf("SIGNAL_ANOMALY"));
        assertEquals(AnomalyType.GNSS_LOST, AnomalyType.valueOf("GNSS_LOST"));
        assertEquals(AnomalyType.GNSS_RECOVERED, AnomalyType.valueOf("GNSS_RECOVERED"));
    }
}
