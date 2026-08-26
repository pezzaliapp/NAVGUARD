# NAVGUARD Android v0.1.0

**Autore unico: Alessandro Pezzali**

NAVGUARD è un proof-of-concept Android nativo per valutare localmente la coerenza dei segnali GNSS con i sensori del dispositivo.

## Obiettivo v0.1.0

- Leggere stato satelliti GNSS.
- Leggere misure GNSS raw quando supportate dal dispositivo.
- Leggere accelerometro lineare e giroscopio.
- Calcolare un indice `GNSS TRUST` 0–100.
- Evidenziare perdita anomala del fix, segnali deboli e salti di posizione incoerenti con l'IMU.
- Distinguere sempre una **anomalia compatibile con interferenza** da una diagnosi certa di jamming/spoofing.

## Privacy e costi

- Nessun permesso Internet nel manifest.
- Nessun server.
- Nessuna API commerciale.
- Nessun account cloud obbligatorio.
- Elaborazione sul dispositivo.
- Costi operativi del software: 0 €.

## Requisiti

- Android 8.0+ (API 26).
- GNSS/GPS.
- Accesso alla posizione precisa.
- Sensori IMU opzionali ma raccomandati.
- Per la telemetria GNSS raw il dispositivo deve supportare `GnssMeasurementsEvent`.

## Build

Il progetto usa Android Gradle Plugin 8.12.2 e compileSdk 36.

Con Android SDK e Gradle disponibili:

```bash
gradle :app:assembleDebug
```

L'APK risultante sarà in:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Test consigliato

Eseguire il primo test all'aperto, con cielo libero, per almeno 2–3 minuti. Verificare:

1. numero satelliti visibili/usati;
2. C/N0 medio;
3. disponibilità delle misure raw;
4. numero di costellazioni;
5. comportamento dell'indice GNSS TRUST da fermo e in movimento.

## Limiti

NAVGUARD non è uno strumento certificato aeronautico, marittimo o di sicurezza. Un telefono da solo non può provare in modo definitivo la presenza di spoofing o jamming. La v0.1.0 è una base sperimentale per misurare anomalie e costruire successivamente fusione sensoriale e dead reckoning.

## Autore

Copyright © 2026 Alessandro Pezzali. Tutti i contributi e i commit del progetto devono mantenere Alessandro Pezzali come unico autore; non aggiungere firme, trailer o righe `Co-authored-by` di terzi o sistemi AI.
