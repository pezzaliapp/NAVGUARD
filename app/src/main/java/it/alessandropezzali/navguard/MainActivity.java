package it.alessandropezzali.navguard;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.GnssAutomaticGainControl;
import android.location.GnssCapabilities;
import android.location.GnssMeasurement;
import android.location.GnssMeasurementsEvent;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import it.alessandropezzali.navguard.integrity.AnomalyEvent;
import it.alessandropezzali.navguard.integrity.AnomalyLog;
import it.alessandropezzali.navguard.integrity.AnomalyType;
import it.alessandropezzali.navguard.integrity.IntegrityAssessment;
import it.alessandropezzali.navguard.integrity.IntegrityEngine;
import it.alessandropezzali.navguard.integrity.MotionAnalyzer;
import it.alessandropezzali.navguard.integrity.MotionState;
import it.alessandropezzali.navguard.integrity.PositionFix;
import it.alessandropezzali.navguard.integrity.SignalSample;
import it.alessandropezzali.navguard.integrity.SubScore;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * NAVGUARD - GNSS integrity monitor.
 * Author: Alessandro Pezzali
 *
 * GNSS analysis stays on device. Only OpenStreetMap tiles are fetched for the map.
 * The scoring itself lives in the Android-free integrity package.
 */
public class MainActivity extends Activity implements SensorEventListener, LocationListener {
    private static final int REQ_LOCATION = 1001;
    /** Periodic re-evaluation, so a lost fix is noticed without waiting for a callback. */
    private static final long TICK_INTERVAL_MS = 2000L;
    /** Floor between two UI refreshes; the periodic tick always gets through. */
    private static final long UI_MIN_INTERVAL_MS = 500L;
    private static final int TRAIL_MAX_POINTS = 30;
    /** The log keeps 100 events; the panel shows only the most recent ones. */
    private static final int LOG_VISIBLE_EVENTS = 10;

    private TextView integrityScoreView;
    private TextView statusView;
    private TextView reasonView;
    private TextView diagnosticsView;
    private TextView subScoresView;
    private TextView anomalyLogView;
    private TextView whyHeaderView;
    private TextView logHeaderView;
    private LinearLayout whyPanel;
    private LinearLayout logPanel;
    private Button clearLogButton;
    private WebView mapView;

    private LocationManager locationManager;
    private SensorManager sensorManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private int satellitesVisible = 0;
    private int satellitesUsed = 0;
    private double avgCn0 = Double.NaN;
    private int constellationCount = 0;
    private boolean rawGnssSeen = false;
    private int rawMeasurements = 0;
    private double avgAgcDb = Double.NaN;

    private boolean imuAvailable = false;
    private boolean magnetometerAvailable = false;
    private float linearAcceleration = 0f;
    private float gyroRate = 0f;

    private Location previousLocation;
    private long previousLocationTimeMs = 0L;
    private Location currentLocation;
    private Location lastTrustedLocation;
    private long lastTrustedTimestampMs = 0L;
    private final List<Location> locationTrail = new ArrayList<>();
    private double lastComputedSpeedMps = 0.0;

    // The integrity engine is the single source of the score. It is Android-free and owns the
    // motion classifier, the rolling signal baseline and the local anomaly log.
    private final MotionAnalyzer motionAnalyzer = new MotionAnalyzer();
    private final AnomalyLog anomalyLog = new AnomalyLog();
    private final IntegrityEngine integrityEngine = new IntegrityEngine(motionAnalyzer, anomalyLog);
    private IntegrityAssessment lastAssessment;
    private int integrityScore = 0;
    private String integrityReason = "In attesa dei dati GNSS.";
    private long lastUiUpdateMs = 0L;
    private final SimpleDateFormat clockFormat = new SimpleDateFormat("HH:mm:ss", Locale.ITALY);

    private final Runnable integrityTick = new Runnable() {
        @Override
        public void run() {
            refreshIntegrity();
            mainHandler.postDelayed(this, TICK_INTERVAL_MS);
        }
    };

    private final GnssStatus.Callback statusCallback = new GnssStatus.Callback() {
        @Override
        public void onSatelliteStatusChanged(GnssStatus status) {
            satellitesVisible = status.getSatelliteCount();
            satellitesUsed = 0;
            double cn0Sum = 0.0;
            int cn0Count = 0;
            Set<Integer> constellations = new HashSet<>();

            for (int i = 0; i < status.getSatelliteCount(); i++) {
                if (status.usedInFix(i)) satellitesUsed++;
                float cn0 = status.getCn0DbHz(i);
                if (!Float.isNaN(cn0) && cn0 > 0f) {
                    cn0Sum += cn0;
                    cn0Count++;
                }
                int constellation = status.getConstellationType(i);
                if (constellation != GnssStatus.CONSTELLATION_UNKNOWN) {
                    constellations.add(constellation);
                }
            }
            avgCn0 = cn0Count > 0 ? cn0Sum / cn0Count : Double.NaN;
            constellationCount = constellations.size();
            publishSignalSample();
        }
    };

    private final GnssMeasurementsEvent.Callback measurementsCallback = new GnssMeasurementsEvent.Callback() {
        @Override
        public void onGnssMeasurementsReceived(GnssMeasurementsEvent eventArgs) {
            rawGnssSeen = true;
            rawMeasurements = eventArgs.getMeasurements().size();
            avgAgcDb = readAverageAgcDb(eventArgs);
            integrityEngine.onRawMeasurements(System.currentTimeMillis(), rawMeasurements);
            publishSignalSample();
        }

        // Deprecated from API 33 but still delivered on older devices, so it is kept.
        @Override
        @SuppressWarnings("deprecation")
        public void onStatusChanged(int status) {
            refreshIntegrity();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        integrityScoreView = findViewById(R.id.integrityScore);
        statusView = findViewById(R.id.statusText);
        reasonView = findViewById(R.id.reasonText);
        diagnosticsView = findViewById(R.id.diagnosticsText);
        subScoresView = findViewById(R.id.subScoresText);
        anomalyLogView = findViewById(R.id.anomalyLogText);
        whyHeaderView = findViewById(R.id.whyHeader);
        logHeaderView = findViewById(R.id.logHeader);
        whyPanel = findViewById(R.id.whyPanel);
        logPanel = findViewById(R.id.logPanel);
        clearLogButton = findViewById(R.id.clearLogButton);
        mapView = findViewById(R.id.mapView);
        configureMap();
        configureExpandableSections();

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        registerImuSensors();

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
        } else {
            startGnssMonitoring();
        }
    }

    private void configureMap() {
        WebSettings settings = mapView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setUserAgentString("NAVGUARD/0.3.0 Android; https://github.com/pezzaliapp/NAVGUARD");
        mapView.setVerticalScrollBarEnabled(false);
        mapView.setHorizontalScrollBarEnabled(false);
        mapView.loadUrl("file:///android_asset/map.html");
    }

    /** Plain framework widgets: a clickable header toggling a panel between VISIBLE and GONE. */
    private void configureExpandableSections() {
        whyHeaderView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean expand = whyPanel.getVisibility() != View.VISIBLE;
                whyPanel.setVisibility(expand ? View.VISIBLE : View.GONE);
                whyHeaderView.setText((expand ? "\u25be  " : "\u25b8  ") + "PERCHÉ QUESTO PUNTEGGIO?");
                if (expand) renderSubScores();
            }
        });
        logHeaderView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean expand = logPanel.getVisibility() != View.VISIBLE;
                logPanel.setVisibility(expand ? View.VISIBLE : View.GONE);
                logHeaderView.setText((expand ? "\u25be  " : "\u25b8  ") + "REGISTRO ANOMALIE");
                if (expand) renderAnomalyLog();
            }
        });
        clearLogButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                anomalyLog.clear();
                renderAnomalyLog();
            }
        });
    }

    private void registerImuSensors() {
        Sensor linear = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        Sensor gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        Sensor magnetic = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        if (linear != null) {
            sensorManager.registerListener(this, linear, SensorManager.SENSOR_DELAY_GAME);
            imuAvailable = true;
        }
        if (gyro != null) {
            sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME);
            imuAvailable = true;
        }
        // Optional. Its absence is not an error and is never penalised: heading is secondary
        // evidence only, and the engine simply skips that check when the sensor is missing.
        if (magnetic != null) {
            sensorManager.registerListener(this, magnetic, SensorManager.SENSOR_DELAY_UI);
            magnetometerAvailable = true;
        }
        motionAnalyzer.setInertialSensorsPresent(imuAvailable);
        motionAnalyzer.setMagnetometerPresent(magnetometerAvailable);
    }

    private void startGnssMonitoring() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        try {
            locationManager.registerGnssStatusCallback(statusCallback, mainHandler);
            locationManager.registerGnssMeasurementsCallback(measurementsCallback, mainHandler);
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper());
        } catch (SecurityException ignored) {
        }
        integrityEngine.onMonitoringStarted(System.currentTimeMillis());
        detectRawGnssSupport();
        mainHandler.removeCallbacks(integrityTick);
        mainHandler.postDelayed(integrityTick, TICK_INTERVAL_MS);
        refreshIntegrity();
    }

    /**
     * AGC average over the values this event actually carries.
     * API 33+ exposes AGC per band on the event itself; the per-measurement getter is deprecated
     * there and is therefore NOT called. Below 33 the per-measurement getter is the only source.
     * When no AGC is reported the result is NaN, which the engine treats as UNAVAILABLE - never
     * as zero and never as a penalty.
     */
    private double readAverageAgcDb(GnssMeasurementsEvent event) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return readAverageAgcApi33(event);
        }
        return readAverageAgcLegacy(event);
    }

    private double readAverageAgcApi33(GnssMeasurementsEvent event) {
        double sum = 0.0;
        int count = 0;
        for (GnssAutomaticGainControl agc : event.getGnssAutomaticGainControls()) {
            double level = agc.getLevelDb();
            if (!Double.isNaN(level)) {
                sum += level;
                count++;
            }
        }
        return count > 0 ? sum / count : Double.NaN;
    }

    @SuppressWarnings("deprecation")
    private double readAverageAgcLegacy(GnssMeasurementsEvent event) {
        double sum = 0.0;
        int count = 0;
        for (GnssMeasurement m : event.getMeasurements()) {
            if (m.hasAutomaticGainControlLevelDb()) {
                double agc = m.getAutomaticGainControlLevelDb();
                if (!Double.isNaN(agc)) {
                    sum += agc;
                    count++;
                }
            }
        }
        return count > 0 ? sum / count : Double.NaN;
    }

    /**
     * GnssCapabilities.hasMeasurements() exists from API 31. Below that Android gives no way to
     * know, so the engine is told "unknown" (null) and simply excludes the component instead of
     * assuming either support or absence.
     */
    private void detectRawGnssSupport() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            integrityEngine.setRawGnssSupported(readRawGnssSupportApi31());
        } else {
            integrityEngine.setRawGnssSupported(null);
        }
    }

    private Boolean readRawGnssSupportApi31() {
        try {
            GnssCapabilities capabilities = locationManager.getGnssCapabilities();
            return capabilities == null ? null : Boolean.valueOf(capabilities.hasMeasurements());
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startGnssMonitoring();
        } else {
            integrityScoreView.setText("0%");
            statusView.setText("PERMESSO NECESSARIO");
            reasonView.setText("NAVGUARD necessita della posizione precisa per leggere GNSS e misure satellitari.");
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        long now = System.currentTimeMillis();
        currentLocation = location;
        lastComputedSpeedMps = location.hasSpeed() ? location.getSpeed() : 0.0;

        if (previousLocation != null && previousLocationTimeMs > 0L) {
            double dt = Math.max(0.001, (now - previousLocationTimeMs) / 1000.0);
            double distance = previousLocation.distanceTo(location);
            lastComputedSpeedMps = Math.max(lastComputedSpeedMps, distance / dt);
        }

        previousLocation = new Location(location);
        previousLocationTimeMs = now;
        locationTrail.add(new Location(location));
        while (locationTrail.size() > TRAIL_MAX_POINTS) locationTrail.remove(0);

        // The fix timestamp uses the same clock as the inertial samples, so the engine can line
        // up a position step with the motion state that was observed at that instant.
        syncProviderState();
        PositionFix fix = new PositionFix(
                location.getLatitude(),
                location.getLongitude(),
                location.hasAccuracy() ? location.getAccuracy() : Float.NaN,
                location.hasSpeed() ? location.getSpeed() : Float.NaN,
                location.hasBearing() ? location.getBearing() : Float.NaN,
                now);
        applyAssessment(integrityEngine.onLocation(fix));

        // The engine decides eligibility; the Activity only mirrors its verdict for the map.
        if (integrityEngine.lastTrustedTimeMs() == now) {
            lastTrustedLocation = new Location(location);
            lastTrustedTimestampMs = now;
        }
    }

    /** Periodic re-evaluation with no new GNSS data; also lets the engine notice a lost fix. */
    private void refreshIntegrity() {
        syncProviderState();
        applyAssessment(integrityEngine.tick(System.currentTimeMillis()));
    }

    /** Pushes the current radio picture into the engine and folds it into the rolling baseline. */
    private void publishSignalSample() {
        long now = System.currentTimeMillis();
        syncProviderState();
        applyAssessment(integrityEngine.onSignalUpdate(new SignalSample(
                now, avgCn0, satellitesVisible, satellitesUsed, constellationCount, avgAgcDb)));
    }

    private void syncProviderState() {
        if (locationManager != null) {
            integrityEngine.setProviderEnabled(
                    locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER));
        }
    }

    private void applyAssessment(IntegrityAssessment assessment) {
        lastAssessment = assessment;
        integrityScore = assessment.available ? assessment.score : 0;
        integrityReason = buildReasonText(assessment);
        long now = System.currentTimeMillis();
        if (now - lastUiUpdateMs >= UI_MIN_INTERVAL_MS) {
            lastUiUpdateMs = now;
            updateUi();
        }
    }

    private String buildReasonText(IntegrityAssessment assessment) {
        if (assessment == null || !assessment.available) {
            return "In attesa dei dati GNSS.";
        }
        if (assessment.reasons.isEmpty()) {
            return "Dati GNSS coerenti con i sensori disponibili.";
        }
        StringBuilder sb = new StringBuilder();
        for (String reason : assessment.reasons) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("\u2022 ").append(reason);
        }
        return sb.toString();
    }

    private int colorForScore(int score) {
        if (score < 0) return Color.rgb(166, 176, 188);
        if (score >= 80) return Color.rgb(118, 230, 177);
        if (score >= 60) return Color.rgb(143, 217, 192);
        if (score >= 40) return Color.rgb(255, 209, 102);
        if (score >= 20) return Color.rgb(255, 159, 69);
        return Color.rgb(255, 107, 107);
    }

    private String motionLabel(MotionState state) {
        if (state == MotionState.STATIONARY) return "FERMO";
        if (state == MotionState.MOVING) return "IN MOVIMENTO";
        return "N/D";
    }

    /** Renders the five components read straight from the assessment. UNAVAILABLE shows N/D. */
    private void renderSubScores() {
        if (subScoresView == null) return;
        if (lastAssessment == null) {
            subScoresView.setText("Segnale       --\nSatelliti     --\nPosizione     --"
                    + "\nMovimento     --\nRaw GNSS      --");
            return;
        }
        List<SubScore> components = lastAssessment.subScores();
        String[] labels = {"Segnale", "Satelliti", "Posizione", "Movimento", "Raw GNSS"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < components.size() && i < labels.length; i++) {
            if (i > 0) sb.append('\n');
            SubScore component = components.get(i);
            String value = component.available ? String.valueOf(component.rounded()) : "N/D";
            sb.append(String.format(Locale.ITALY, "%-13s %s", labels[i], value));
        }
        subScoresView.setText(sb.toString());
    }

    /** Shows the most recent events; the log itself keeps its own 100-entry FIFO cap. */
    private void renderAnomalyLog() {
        if (anomalyLogView == null) return;
        List<AnomalyEvent> events = anomalyLog.recent(LOG_VISIBLE_EVENTS);
        if (events.isEmpty()) {
            anomalyLogView.setText("Nessun evento registrato.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (AnomalyEvent event : events) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(clockFormat.format(new Date(event.timeMs)))
                    .append("  ")
                    .append(anomalyTypeLabel(event.type))
                    .append('\n')
                    .append(event.reason);
        }
        if (anomalyLog.size() > events.size()) {
            sb.append("\n\n(").append(events.size()).append(" di ")
                    .append(anomalyLog.size()).append(" eventi in memoria)");
        }
        anomalyLogView.setText(sb.toString());
    }

    private String anomalyTypeLabel(AnomalyType type) {
        if (type == AnomalyType.POSITION_JUMP) return "Salto posizione";
        if (type == AnomalyType.GNSS_IMU_CONFLICT) return "Incoerenza GNSS/IMU";
        if (type == AnomalyType.SPEED_CONFLICT) return "Incoerenza velocità";
        if (type == AnomalyType.SIGNAL_ANOMALY) return "Anomalia segnale";
        if (type == AnomalyType.GNSS_LOST) return "GNSS perso";
        if (type == AnomalyType.GNSS_RECOVERED) return "GNSS ripristinato";
        return type.name();
    }

    private String formatClock(long timeMs) {
        return timeMs <= 0L ? "—" : clockFormat.format(new Date(timeMs));
    }

    private void updateUi() {
        runOnUiThread(() -> {
            boolean available = lastAssessment != null && lastAssessment.available;
            integrityScoreView.setText(available ? (integrityScore + "%") : "--%");
            int color = colorForScore(available ? integrityScore : -1);
            String status = available ? lastAssessment.level.label : "IN ATTESA DEL GNSS";

            integrityScoreView.setTextColor(color);
            statusView.setText(status);
            statusView.setTextColor(color);
            reasonView.setText(integrityReason);

            String cn0 = Double.isNaN(avgCn0) ? "N/D" : String.format(Locale.ITALY, "%.1f dB-Hz", avgCn0);
            String agc = Double.isNaN(avgAgcDb) ? "N/D" : String.format(Locale.ITALY, "%.1f dB", avgAgcDb);
            String imu = !imuAvailable ? "N/D" : String.format(Locale.ITALY,
                    "acc %.2f m/s² • gyro %.2f rad/s", linearAcceleration, gyroRate);
            MotionState motionState = lastAssessment == null
                    ? motionAnalyzer.stateAt(System.currentTimeMillis())
                    : lastAssessment.motionState;
            String accuracy = (currentLocation == null || !currentLocation.hasAccuracy())
                    ? "N/D"
                    : String.format(Locale.ITALY, "±%.0f m", currentLocation.getAccuracy());
            String position = currentLocation == null ? "N/D" : String.format(Locale.ITALY,
                    "%.5f, %.5f", currentLocation.getLatitude(), currentLocation.getLongitude());
            String lastFix = previousLocationTimeMs <= 0L ? "N/D" : formatClock(previousLocationTimeMs);

            diagnosticsView.setText(
                    "Satelliti visibili:  " + satellitesVisible +
                    "\nSatelliti usati:     " + satellitesUsed +
                    "\nCostellazioni:       " + constellationCount +
                    "\nC/N0 medio:          " + cn0 +
                    "\nRaw GNSS:            " + (rawGnssSeen ? (rawMeasurements + " misure") : "in attesa") +
                    "\nAGC medio:           " + agc +
                    "\nIMU:                 " + imu +
                    "\nMagnetometro:        " + (magnetometerAvailable ? "disponibile" : "N/D") +
                    "\nMovimento:           " + motionLabel(motionState) +
                    "\nVelocità stimata:    " + String.format(Locale.ITALY, "%.1f m/s", lastComputedSpeedMps) +
                    "\nAccuracy:            " + accuracy +
                    "\nPosizione:           " + position +
                    "\nUltimo fix:          " + lastFix +
                    "\nUltima pos. affid.:  " + formatClock(lastTrustedTimestampMs)
            );

            // Advanced panels are refreshed only while they are actually on screen.
            if (whyPanel != null && whyPanel.getVisibility() == View.VISIBLE) renderSubScores();
            if (logPanel != null && logPanel.getVisibility() == View.VISIBLE) renderAnomalyLog();
            updateMap();
        });
    }

    private void updateMap() {
        if (mapView == null || currentLocation == null) return;
        double trustedLat = lastTrustedLocation == null ? Double.NaN : lastTrustedLocation.getLatitude();
        double trustedLon = lastTrustedLocation == null ? Double.NaN : lastTrustedLocation.getLongitude();
        StringBuilder trailJson = new StringBuilder("[");
        for (int i = 0; i < locationTrail.size(); i++) {
            if (i > 0) trailJson.append(',');
            Location p = locationTrail.get(i);
            trailJson.append('[')
                    .append(String.format(Locale.US, "%.7f", p.getLatitude()))
                    .append(',')
                    .append(String.format(Locale.US, "%.7f", p.getLongitude()))
                    .append(']');
        }
        trailJson.append(']');

        String js = String.format(Locale.US,
                "window.navguardUpdate(%.7f,%.7f,%.1f,%d,%s,%s,%s);",
                currentLocation.getLatitude(),
                currentLocation.getLongitude(),
                currentLocation.hasAccuracy() ? currentLocation.getAccuracy() : 0f,
                integrityScore,
                Double.isNaN(trustedLat) ? "null" : String.format(Locale.US, "%.7f", trustedLat),
                Double.isNaN(trustedLon) ? "null" : String.format(Locale.US, "%.7f", trustedLon),
                trailJson.toString());
        mapView.evaluateJavascript(js, null);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        long now = System.currentTimeMillis();
        int type = event.sensor.getType();
        if (type == Sensor.TYPE_LINEAR_ACCELERATION) {
            linearAcceleration = magnitude(event.values);
            motionAnalyzer.onInertialSample(now, linearAcceleration, gyroRate);
        } else if (type == Sensor.TYPE_GYROSCOPE) {
            gyroRate = magnitude(event.values);
            motionAnalyzer.onInertialSample(now, linearAcceleration, gyroRate);
        } else if (type == Sensor.TYPE_MAGNETIC_FIELD && event.values != null
                && event.values.length >= 3) {
            // Secondary indicator only: it can suppress a heading check, never raise an anomaly.
            motionAnalyzer.onMagneticSample(now, event.values[0], event.values[1], event.values[2]);
        }
    }

    private float magnitude(float[] v) {
        if (v == null || v.length < 3) return 0f;
        return (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    @Override public void onProviderEnabled(String provider) { refreshIntegrity(); }
    @Override public void onProviderDisabled(String provider) { refreshIntegrity(); }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacks(integrityTick);
        sensorManager.unregisterListener(this);
        if (mapView != null) mapView.destroy();
        try {
            locationManager.removeUpdates(this);
            locationManager.unregisterGnssStatusCallback(statusCallback);
            locationManager.unregisterGnssMeasurementsCallback(measurementsCallback);
        } catch (Exception ignored) {
        }
    }
}
