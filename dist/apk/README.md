# APKs debug — Monitor PPG Forense

> **Subidos manualmente** sólo para descarga rápida desde la PR. El `.gitignore` global del repo ignora `*.apk`; aquí se hace excepción.

## ForensicPPG-debug-v3-no-crash-37b3.apk (RECOMENDADO)

- Tamaño: 9.4 MB
- SHA-256: `4bc61f376d96669869b73ca5c7e188ecdd3affbc844f96f8b6762f56027bfefd`
- versionName: `1.0.0-forensic`
- minSdk 26 (Android 8.0+) · targetSdk 35

Soluciona el cierre inmediato al abrir:

- Nueva clase `ForensicPpgApp : Application` registrada en el manifest, instala un `CrashLogger` global ANTES de que cualquier ViewModel / Activity ejecute código.
- Cuando hay un crash el stack trace se escribe a `/sdcard/Android/data/com.forensicppg.monitor/files/crash-<timestamp>.txt` y se muestra en la próxima apertura de la app.
- `BeatFeedbackController` ahora construye el `ToneGenerator` de forma **lazy** y dentro de `try/catch` (era una causa frecuente de crash en algunos OEMs).
- `MainActivity` ya **NO arranca la cámara automáticamente** en `onCreate`/`onResume`. El usuario debe pulsar INICIAR. Antes Camera2 podía explotar al abrir la app si otra app aún tenía la cámara o si el permiso recién se concedía.
- Quitado `screenOrientation="landscape"` y `Theme.ForensicPPG.FullScreen` con `windowFullscreen=true` que en algunos modelos disparaban excepciones del `WindowInsetsController` durante la creación de la activity. Setup mínimo: solo `FLAG_KEEP_SCREEN_ON`, en try/catch.
- `BeatFeedbackController.vibrator` también capturado en `runCatching`.
- Pantalla de permiso muestra el último crash report si hubo uno.

## ForensicPPG-debug-v2-acquisition-fix-37b3.apk

Versión anterior — adquisición arreglada (SSF + R/G + gates desbloqueados) pero sin el crash handler. Si te crashea esta, esperar a la v3.

## ForensicPPG-debug-v1.0.0-cursor-37b3.apk

Versión inicial.

## Cómo instalar

1. Descargá el `.apk` directamente desde GitHub (botón "Download raw" sobre el archivo en la PR, o usá los enlaces "raw.githubusercontent.com" desde el navegador del teléfono).
2. En el teléfono, habilitá **Orígenes desconocidos** (Ajustes → Seguridad → Instalación de fuentes externas) para el navegador o gestor de archivos.
3. Abrí el `.apk` y confirmá la instalación.
4. Concedé permiso de **Cámara** al iniciar.
5. Pulsá **INICIAR** para abrir la cámara y empezar la captura.
6. Seguí la guía única: yema del **dedo índice de la mano no dominante** cubriendo cámara + flash, presión ligera, mano y codo apoyados, antebrazo a la altura del corazón, inmóvil 3 s.

Si la app vuelve a crashear, **abrila de nuevo**: en la pantalla de permisos verás el stack trace del crash anterior. También está guardado en `Android/data/com.forensicppg.monitor/files/crash-*.txt`.

## Firma

Todos firmados con la **debug keystore** de Android. NO aptos para Play Store.
