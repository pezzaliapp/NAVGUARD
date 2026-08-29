package it.alessandropezzali.navguard.integrity;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.List;

import org.junit.Test;

/**
 * NAVGUARD must never present itself as a spoofing or jamming detector.
 * These checks read the shipped sources directly, so a careless edit to the UI is caught here
 * rather than on the device.
 */
public class NoOverclaimTest {

    private static final String[] BANNED = {
            "spoofing rilevato",
            "jamming rilevato",
            "possibile spoofing",
            "possibile interferenza",
            "spoofing detected",
            "jamming detected"
    };

    /** Unit tests run from the module directory, but tolerate being run from the repo root. */
    private File source(String relative) {
        File direct = new File(relative);
        if (direct.isFile()) return direct;
        File fromRoot = new File("app/" + relative);
        if (fromRoot.isFile()) return fromRoot;
        File fromModule = new File("../app/" + relative);
        if (fromModule.isFile()) return fromModule;
        throw new IllegalStateException("sorgente non trovato: " + relative
                + " (cwd=" + new File(".").getAbsolutePath() + ")");
    }

    private List<String> lines(String relative) throws IOException {
        return Files.readAllLines(source(relative).toPath(), Charset.forName("UTF-8"));
    }

    private static final String LAYOUT = "src/main/res/layout/activity_main.xml";
    private static final String ACTIVITY = "src/main/java/it/alessandropezzali/navguard/MainActivity.java";
    private static final String MAP = "src/main/assets/map.html";

    private void assertNoBannedPhrase(String relative) throws IOException {
        for (String line : lines(relative)) {
            String lower = line.toLowerCase();
            for (String banned : BANNED) {
                assertFalse(relative + " contiene \"" + banned + "\"", lower.contains(banned));
            }
        }
    }

    @Test
    public void theLayoutNeverClaimsSpoofingOrJamming() throws IOException {
        assertNoBannedPhrase(LAYOUT);
    }

    @Test
    public void theActivityNeverClaimsSpoofingOrJamming() throws IOException {
        assertNoBannedPhrase(ACTIVITY);
    }

    @Test
    public void theMapNeverClaimsSpoofingOrJamming() throws IOException {
        assertNoBannedPhrase(MAP);
    }

    /**
     * The words themselves are allowed in exactly one place: the disclaimer stating that
     * NAVGUARD does NOT certify their presence.
     */
    @Test
    public void spoofingAndJammingAppearOnlyInsideTheDisclaimer() throws IOException {
        int mentions = 0;
        for (String line : lines(LAYOUT)) {
            String lower = line.toLowerCase();
            if (lower.contains("spoofing") || lower.contains("jamming")) {
                mentions++;
                assertTrue("menzione fuori dal disclaimer: " + line.trim(),
                        lower.contains("non certifica la presenza di jamming o spoofing"));
            }
        }
        assertTrue("il disclaimer deve esserci", mentions >= 1);
    }

    @Test
    public void theActivityDoesNotMentionSpoofingOrJammingAtAll() throws IOException {
        for (String line : lines(ACTIVITY)) {
            String lower = line.toLowerCase();
            assertFalse(lower.contains("spoofing"));
            assertFalse(lower.contains("jamming"));
        }
    }

    @Test
    public void theUiDoesNotClaimTheAppIsSafeOrOffline() throws IOException {
        for (String line : lines(LAYOUT)) {
            String lower = line.toLowerCase();
            assertFalse("non promettere assenza totale di rete: " + line.trim(),
                    lower.contains("completamente offline") || lower.contains("totalmente offline"));
            assertFalse("evitare il lessico 'sicuro/safe': " + line.trim(),
                    lower.contains(">safe<") || lower.contains("\"safe\""));
        }
    }

    @Test
    public void theDisclaimerCarriesAllItsClauses() throws IOException {
        StringBuilder all = new StringBuilder();
        for (String line : lines(LAYOUT)) all.append(line).append('\n');
        String text = all.toString();
        assertTrue(text.contains("strumento sperimentale di monitoraggio"));
        assertTrue(text.contains("Non certifica la presenza di jamming o spoofing"));
        assertTrue(text.contains("non determina l'origine delle anomalie"));
        assertTrue(text.contains("ambiente, ostacoli, hardware"));
        assertTrue(text.contains("aeronautico, marittimo, militare"));
        assertTrue(text.contains("decisioni critiche di navigazione"));
        assertTrue(text.contains("La mappa OpenStreetMap richiede accesso Internet"));
        assertTrue(text.contains("analisi GNSS viene eseguita localmente"));
    }

    @Test
    public void theUiShowsTheIntegrityWordingAndVersion() throws IOException {
        StringBuilder all = new StringBuilder();
        for (String line : lines(LAYOUT)) all.append(line).append('\n');
        String text = all.toString();
        assertTrue(text.contains("Android v0.3.0"));
        assertTrue(text.contains("GNSS Integrity Monitor"));
        assertTrue(text.contains("GNSS INTEGRITY SCORE"));
        assertFalse(text.toLowerCase().contains("gnss trust"));
    }

    @Test
    public void theEngineNeverProducesAnAccusatoryReason() {
        MotionAnalyzer analyzer = new MotionAnalyzer();
        IntegrityEngine engine = TestSupport.warmEngine(analyzer, new AnomalyLog(), 1000L);
        TestSupport.quietImu(analyzer, 9000L, 10_000L);
        engine.onLocation(TestSupport.fix(45.4640, 9.1900, 4f, 0f, 10_000L));
        TestSupport.quietImu(analyzer, 10_100L, 12_000L);
        IntegrityAssessment jumped = engine.onLocation(
                TestSupport.fix(45.4640 + TestSupport.latOffsetForMeters(900.0), 9.1900,
                        4f, 0f, 12_000L));

        for (String reason : jumped.reasons) {
            String lower = reason.toLowerCase();
            assertFalse(reason, lower.contains("spoof"));
            assertFalse(reason, lower.contains("jamming"));
            assertFalse(reason, lower.contains("attacco"));
        }
        for (AnomalyEvent event : jumped.newEvents) {
            String lower = event.reason.toLowerCase();
            assertFalse(event.reason, lower.contains("spoof"));
            assertFalse(event.reason, lower.contains("jamming"));
        }
    }
}
