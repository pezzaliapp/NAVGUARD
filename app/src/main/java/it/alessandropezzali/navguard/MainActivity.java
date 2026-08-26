package it.alessandropezzali.navguard;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.GnssMeasurement;
import android.location.GnssMeasurementsEvent;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.widget.TextView;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * NAVGUARD - GNSS integrity monitor.
 * Author: Alessandro Pezzali
 *
 * Local-only proof of concept. No network permission is requested.
 */
public class MainActivity extends Activity implements SensorEventListener, LocationListener {
    private static final int REQ_LOCATION = 1001;

    private TextView trustScoreView;
    private TextView statusView;
    private TextView reasonView;
    private TextView diagnosticsView;

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
    private float linearAcceleration = 0f;
    private float gyroRate = 0f;
    private long lastImuMovementMs = 0L;

    private Location previousLocation;
    private long previousLocationTimeMs = 0L;
    private Location currentLocation;
    private boolean suspiciousJump = false;
    private double lastJumpMeters = 0.0;
    private double lastComputedSpeedMps = 0.0;

    private long lastGoodGnssMs = 0L;
    private int lastTrust = 0;
    private String integrityReason = "In attesa dei dati GNSS.";

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
            recomputeIntegrity();
        }
    };

    private final GnssMeasurementsEvent.Callback measurementsCallback = new GnssMeasurementsEvent.Callback() {
        @Override
        public void onGnssMeasurementsReceived(GnssMeasurementsEvent eventArgs) {
            rawGnssSeen = true;
            rawMeasurements = eventArgs.getMeasurements().size();
            double agcSum = 0.0;
            int agcCount = 0;
            for (GnssMeasurement m : eventArgs.getMeasurements()) {
                if (m.hasAutomaticGainControlLevelDb()) {
                    double agc = m.getAutomaticGainControlLevelDb();
                    if (!Double.isNaN(agc)) {
                        agcSum += agc;
                        agcCount++;
                    }
                }
            }
            avgAgcDb = agcCount > 0 ? agcSum / agcCount : Double.NaN;
            recomputeIntegrity();
        }

        @Override
        public void onStatusChanged(int status) {
            recomputeIntegrity();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        trustScoreView = findViewById(R.id.trustScore);
        statusView = findViewById(R.id.statusText);
        reasonView = findViewById(R.id.reasonText);
        diagnosticsView = findViewById(R.id.diagnosticsText);

        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        registerImuSensors();

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_LOCATION);
        } else {
            startGnssMonitoring();
        }
    }

    private void registerImuSensors() {
        Sensor linear = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        Sensor gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        if (linear != null) {
            sensorManager.registerListener(this, linear, SensorManager.SENSOR_DELAY_GAME);
            imuAvailable = true;
        }
        if (gyro != null) {
            sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME);
            imuAvailable = true;
        }
    }

    private void startGnssMonitoring() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return;
        try {
            locationManager.registerGnssStatusCallback(statusCallback, mainHandler);
            locationManager.registerGnssMeasurementsCallback(measurementsCallback, mainHandler);
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper());
        } catch (SecurityException ignored) {
        }
        recomputeIntegrity();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startGnssMonitoring();
        } else {
            trustScoreView.setText("0%");
            statusView.setText("PERMESSO NECESSARIO");
            reasonView.setText("NAVGUARD necessita della posizione precisa per leggere GNSS e misure satellitari.");
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        long now = System.currentTimeMillis();
        currentLocation = location;
        suspiciousJump = false;
        lastJumpMeters = 0.0;
        lastComputedSpeedMps = location.hasSpeed() ? location.getSpeed() : 0.0;

        if (previousLocation != null && previousLocationTimeMs > 0L) {
            double dt = Math.max(0.001, (now - previousLocationTimeMs) / 1000.0);
            double distance = previousLocation.distanceTo(location);
            double derivedSpeed = distance / dt;
            lastComputedSpeedMps = Math.max(lastComputedSpeedMps, derivedSpeed);
            boolean imuQuiet = (now - lastImuMovementMs) > 2500L;

            // Conservative anomaly rule: a large, very fast position jump while the IMU reports no movement.
            if (distance > 250.0 && derivedSpeed > 80.0 && imuQuiet) {
                suspiciousJump = true;
                lastJumpMeters = distance;
            }
        }

        previousLocation = new Location(location);
        previousLocationTimeMs = now;
        recomputeIntegrity();
    }

    private void recomputeIntegrity() {
        int score = 100;
        StringBuilder reasons = new StringBuilder();
        long now = System.currentTimeMillis();

        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            score = 0;
            reasons.append("GNSS disattivato sul dispositivo. ");
        } else {
            if (satellitesVisible == 0) {
                score -= 55;
                reasons.append("Nessun satellite visibile. ");
            } else if (satellitesVisible < 6) {
                score -= 20;
                reasons.append("Pochi satelliti visibili. ");
            }

            if (satellitesUsed < 4) {
                score -= 30;
                reasons.append("Fix debole: meno di 4 satelliti utilizzati. ");
            } else if (satellitesUsed >= 6) {
                lastGoodGnssMs = now;
            }

            if (!Double.isNaN(avgCn0)) {
                if (avgCn0 < 15.0) {
                    score -= 30;
                    reasons.append("Potenza media dei segnali molto bassa. ");
                } else if (avgCn0 < 22.0) {
                    score -= 15;
                    reasons.append("Potenza media dei segnali ridotta. ");
                }
            }

            if (constellationCount <= 1 && satellitesVisible >= 4) {
                score -= 10;
                reasons.append("Bassa diversità delle costellazioni. ");
            }

            if (suspiciousJump) {
                score -= 55;
                reasons.append(String.format(Locale.ITALY,
                        "Salto di posizione incoerente con l'IMU (%.0f m). ", lastJumpMeters));
            }

            // A sudden collapse shortly after a healthy fix is more suspicious than a cold start indoors.
            if (lastGoodGnssMs > 0 && (now - lastGoodGnssMs) < 15000L && satellitesUsed < 2 && satellitesVisible < 4) {
                score -= 15;
                reasons.append("Perdita improvvisa del fix dopo una ricezione valida. ");
            }

            if (!rawGnssSeen) {
                score -= 5;
                reasons.append("Misure GNSS raw non ancora disponibili. ");
            }
        }

        score = Math.max(0, Math.min(100, score));
        lastTrust = score;
        if (reasons.length() == 0) reasons.append("Dati GNSS coerenti con i sensori disponibili.");
        integrityReason = reasons.toString().trim();
        updateUi();
    }

    private void updateUi() {
        runOnUiThread(() -> {
            trustScoreView.setText(lastTrust + "%");
            int color;
            String status;

            if (lastTrust >= 75) {
                color = Color.rgb(118, 230, 177);
                status = "GNSS COERENTE";
            } else if (lastTrust >= 45) {
                color = Color.rgb(255, 209, 102);
                status = "ATTENDIBILITÀ RIDOTTA";
            } else {
                color = Color.rgb(255, 107, 107);
                status = suspiciousJump ? "POSSIBILE SPOOFING / ANOMALIA" : "POSSIBILE INTERFERENZA";
            }

            trustScoreView.setTextColor(color);
            statusView.setText(status);
            statusView.setTextColor(color);
            reasonView.setText(integrityReason);

            String fix = currentLocation == null ? "--" : String.format(Locale.ITALY,
                    "%.5f, %.5f ±%.0f m", currentLocation.getLatitude(), currentLocation.getLongitude(),
                    currentLocation.hasAccuracy() ? currentLocation.getAccuracy() : 0f);
            String cn0 = Double.isNaN(avgCn0) ? "--" : String.format(Locale.ITALY, "%.1f", avgCn0);
            String agc = Double.isNaN(avgAgcDb) ? "--" : String.format(Locale.ITALY, "%.1f dB", avgAgcDb);
            String imu = !imuAvailable ? "non disponibile" : String.format(Locale.ITALY,
                    "acc %.2f m/s² • gyro %.2f rad/s", linearAcceleration, gyroRate);

            diagnosticsView.setText(
                    "Satelliti: " + satellitesVisible +
                    "\nUsati nel fix: " + satellitesUsed +
                    "\nC/N0 medio: " + cn0 + " dB-Hz" +
                    "\nCostellazioni: " + constellationCount +
                    "\nRaw GNSS: " + (rawGnssSeen ? (rawMeasurements + " misure") : "in attesa") +
                    "\nAGC medio: " + agc +
                    "\nIMU: " + imu +
                    "\nVelocità stimata: " + String.format(Locale.ITALY, "%.1f m/s", lastComputedSpeedMps) +
                    "\nUltimo fix: " + fix
            );
        });
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) {
            linearAcceleration = magnitude(event.values);
            if (linearAcceleration > 0.7f) lastImuMovementMs = System.currentTimeMillis();
        } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            gyroRate = magnitude(event.values);
            if (gyroRate > 0.12f) lastImuMovementMs = System.currentTimeMillis();
        }
    }

    private float magnitude(float[] v) {
        if (v == null || v.length < 3) return 0f;
        return (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    @Override public void onProviderEnabled(String provider) { recomputeIntegrity(); }
    @Override public void onProviderDisabled(String provider) { recomputeIntegrity(); }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        sensorManager.unregisterListener(this);
        try {
            locationManager.removeUpdates(this);
            locationManager.unregisterGnssStatusCallback(statusCallback);
            locationManager.unregisterGnssMeasurementsCallback(measurementsCallback);
        } catch (Exception ignored) {
        }
    }
}
