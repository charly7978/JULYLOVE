# APKs debug — Monitor PPG Forense

> **Subidos manualmente** sólo para descarga rápida desde la PR. El `.gitignore` global del repo ignora `*.apk`; aquí se hace excepción.

## ForensicPPG-debug-v5-camera-npe-fix-37b3.apk (RECOMENDADO)

- Tamaño: 9.8 MB
- SHA-256: `330f5703a559553ed4d54ba137d8ef26a566ca4cb4bccf276c198a9b09025f0b`

Arregla el crash detectado en TCL T803E (Android 15):

```
java.lang.NullPointerException: Attempt to invoke virtual method
'void android.media.Image.close()' on a null object reference
at Camera2PpgController.kt:244 (thread: ppg-camera2)
```

`ImageReader.acquireNextImage()` puede devolver **null** legalmente cuando el reader se queda momentáneamente sin imágenes (la API doc lo dice: "Returns: latest frame of image data, or null if no image data is available"). El v4 hacía `img.close()` en un `finally` con `img` no-nullable → NPE.

**Crítico**: el callback corre en `HandlerThread "ppg-camera2"`, FUERA de cualquier corrutina. Por eso el `CoroutineExceptionHandler` del v4 no podía interceptarlo. Solución:

- `acquireNextImage()` tipado como nullable; si es null, `return` limpio.
- Procesamiento de imagen extraído a `drainReader(ir)` con `try/catch` interno.
- El callback completo envuelto en `try/catch` que reporta no-fatal.
- `analyzer.analyze()` también en `try/catch` propio.
- `img.close()` en `runCatching`.
- `onCaptureCompleted` (otro callback fuera de corrutina) también blindado.

## ForensicPPG-debug-v4-safe-mode-37b3.apk

- Tamaño: 9.8 MB
- SHA-256: `c6de8e838b5f140b7d9be314458e7648c31be08feafe787654e2349400ebf28a`
- versionName: `1.0.0-forensic`

Soluciona el cierre al tocar botones:

- `MonitorViewModel` ahora usa un `safeScope = viewModelScope + SupervisorJob() + CoroutineExceptionHandler`. Cualquier excepción del `frameFlow` de Camera2 (que antes viajaba al thread principal y mataba la app) ahora se atrapa, se loguea como **no-fatal** y se muestra al usuario en pantalla.
- Cada `onEach` y `tryEmit` envuelto en `try/catch`; el flujo continúa.
- Nueva `SafeModeScreen` que aparece automáticamente cuando hay un crash o no-fatal: muestra el stack trace y tiene **botón "Compartir reporte"** (Intent `ACTION_SEND` + `FileProvider`) para mandarlo por WhatsApp/email/etc.
- `SafeContent` Compose envuelve toda la UI: si una pantalla falla en la composición, no se cierra la app sino que se muestra el stack trace.
- `viewModel.start()` y `stop()` 100 % en `runCatching`.
- `BeatFeedbackController` ya era a prueba de fallos desde v3.

## ForensicPPG-debug-v3-no-crash-37b3.apk

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
