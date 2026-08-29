package it.alessandropezzali.navguard.integrity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WeightRenormalizationTest {

    private static final double[] WEIGHTS = {
            IntegrityEngine.W_SIGNAL,
            IntegrityEngine.W_SATELLITE,
            IntegrityEngine.W_POSITION,
            IntegrityEngine.W_MOTION,
            IntegrityEngine.W_RAW
    };

    @Test
    public void nominalWeightsAreTheDocumentedOnes() {
        assertEquals(0.25, IntegrityEngine.W_SIGNAL, 1e-9);
        assertEquals(0.20, IntegrityEngine.W_SATELLITE, 1e-9);
        assertEquals(0.30, IntegrityEngine.W_POSITION, 1e-9);
        assertEquals(0.20, IntegrityEngine.W_MOTION, 1e-9);
        assertEquals(0.05, IntegrityEngine.W_RAW, 1e-9);
        double total = 0.0;
        for (double w : WEIGHTS) total += w;
        assertEquals(1.0, total, 1e-9);
    }

    @Test
    public void allComponentsAvailableGivesThePlainWeightedMean() {
        SubScore[] scores = {
                SubScore.of("signal", 100), SubScore.of("satellite", 100),
                SubScore.of("position", 100), SubScore.of("motion", 100),
                SubScore.of("raw", 100)
        };
        assertEquals(100.0, IntegrityEngine.weightedScore(scores, WEIGHTS), 1e-9);
    }

    /** The scenario named in the plan: Signal 80, Position 40, everything else unavailable. */
    @Test
    public void unavailableComponentsAreExcludedAndWeightsRenormalised() {
        SubScore[] scores = {
                SubScore.of("signal", 80), SubScore.unavailable("satellite"),
                SubScore.of("position", 40), SubScore.unavailable("motion"),
                SubScore.unavailable("raw")
        };
        double expected = (0.25 * 80 + 0.30 * 40) / (0.25 + 0.30);
        double actual = IntegrityEngine.weightedScore(scores, WEIGHTS);
        assertEquals(58.1818, actual, 0.01);
        assertEquals(expected, actual, 1e-9);
    }

    @Test
    public void unavailableIsNotTheSameAsZero() {
        SubScore[] unavailable = {
                SubScore.of("signal", 80), SubScore.unavailable("satellite"),
                SubScore.of("position", 40), SubScore.unavailable("motion"),
                SubScore.unavailable("raw")
        };
        SubScore[] zeroed = {
                SubScore.of("signal", 80), SubScore.of("satellite", 0),
                SubScore.of("position", 40), SubScore.of("motion", 0),
                SubScore.of("raw", 0)
        };
        double withUnavailable = IntegrityEngine.weightedScore(unavailable, WEIGHTS);
        double withZeros = IntegrityEngine.weightedScore(zeroed, WEIGHTS);
        assertEquals(32.0, withZeros, 1e-9);
        assertTrue(withUnavailable > withZeros);
        assertTrue("il risultato non deve essere 32", Math.abs(withUnavailable - 32.0) > 20.0);
    }

    @Test
    public void aSingleAvailableComponentBecomesTheWholeScore() {
        SubScore[] scores = {
                SubScore.unavailable("signal"), SubScore.unavailable("satellite"),
                SubScore.of("position", 73), SubScore.unavailable("motion"),
                SubScore.unavailable("raw")
        };
        assertEquals(73.0, IntegrityEngine.weightedScore(scores, WEIGHTS), 1e-9);
    }

    @Test
    public void nothingMeasurableGivesNaNRatherThanZero() {
        SubScore[] scores = {
                SubScore.unavailable("signal"), SubScore.unavailable("satellite"),
                SubScore.unavailable("position"), SubScore.unavailable("motion"),
                SubScore.unavailable("raw")
        };
        assertTrue(Double.isNaN(IntegrityEngine.weightedScore(scores, WEIGHTS)));
    }

    @Test
    public void subScoresAreClampedIntoRange() {
        assertEquals(100.0, SubScore.of("x", 140).value, 1e-9);
        assertEquals(0.0, SubScore.of("x", -20).value, 1e-9);
        assertEquals(-1, SubScore.unavailable("x").rounded());
    }
}
