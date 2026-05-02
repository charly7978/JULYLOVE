# Monitor PPG Forense

App Android nativa (Kotlin + Jetpack Compose) de medición PPG con dedo índice
apoyado sobre la cámara trasera y flash del celular. Visualización tipo
monitor médico/forense con señal 100% derivada de frames reales de la cámara
y del acelerómetro del dispositivo.

## Decisiones clave

- **Sin simulación**: ninguna parte del código utiliza `Math.random`,
  `kotlin.random.Random`, ondas sintéticas o placeholders. Si la señal no es
  confiable, la app muestra un mensaje explícito (sin contacto, saturación,
  baja perfusión, movimiento, calibración faltante, etc.) en lugar de inventar
  un valor.
- **SpO₂ sólo con calibración**: el número absoluto aparece únicamente cuando
  existe un `CalibrationProfile` ajustado con referencia externa y guardado en
  disco. Sin perfil, la UI muestra "SpO₂ requiere calibración".
- **Pipeline completo en Kotlin puro** con detrending, bandpass Butterworth
  orden 4 (0.5–4 Hz), detector de picos Elgendi, detector derivativo
  morfológico, fusión temporal/espectral y SQI compuesto.
- **Camera2 API**: control manual de exposición, ISO, duración de frame,
  torch, AE/AWB lock cuando el hardware lo soporta. Si no lo soporta, se
  aplica la mejor configuración disponible y la UI reporta "control manual
  parcial".

## Estructura

```
app/src/main/java/com/forensicppg/monitor/
├── camera/          Camera2 controller, capabilities, torch, exposure, selector
├── sensors/         Acelerómetro + giroscopio + estimador de motion score
├── ppg/             DSP: filtros, detectores, fusión, SQI, SpO₂, calibración
├── pipeline/        PpgPipeline (orquestador DSP)
├── forensic/        Sesión, eventos, hash SHA-256, exportador JSON/CSV
├── domain/          CameraFrame, PpgSample, VitalReading, estados
├── ui/              MainActivity + Compose (full-screen monitor, tiles, canvas)
```

## Compilar

Requiere Android SDK (platform `android-35`, build-tools `35.0.0`) y JDK 17+.
En este entorno cloud ya están instalados bajo `/opt/android-sdk`.

```bash
./gradlew :app:assembleDebug        # construye app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # 27 tests unitarios del DSP
```

## Uso

1. Instalar el APK en un dispositivo Android 8.0+ (API 26).
2. Al abrir, pide permiso de cámara y entra directo al monitor a pantalla
   completa.
3. Presionar **INICIAR**, cubrir cámara trasera y flash con el dedo índice.
4. Esperar el calentamiento óptico; el monitor mostrará BPM, RR, PI, SQI,
   FPS real, motion score y cribado de arritmia en base a latidos reales.
5. Para habilitar SpO₂ absoluto, presionar **CALIBRAR** y capturar al menos
   3 puntos con un oxímetro de referencia en paralelo.
6. **EXPORTAR** genera JSON + CSVs + reporte técnico + hash SHA-256 de
   integridad en `files/export/<sessionId>/`.

## Notas técnicas

- La presión arterial no se reporta como valor absoluto sistólico/diastólico;
  se entrega como cribado cualitativo (normotenso / limítrofe /
  patrón hipertensivo / indeterminado) siguiendo la recomendación de los
  estudios referenciados en el enunciado: priorizar detección temprana por
  encima de estimar mmHg con un teléfono.
- No se guardan frames crudos ni video por defecto.
