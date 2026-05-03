# Monitor PPG Forense

Medición PPG con dedo sobre cámara + flash en **Android nativo** (`app/`).

La antigua PWA en `web/` fue **retirada del repositorio** para evitar duplicar captura,
ROI, SQI y DSP frente al pipeline nativo. Una copia de trabajo opcional puede vivir en
`_archived_web_ppg_duplicate/` (local, ignorada por git).

---

## Android — `/app`

Kotlin + Jetpack Compose + Camera2; control manual de exposición, ISO, frame duration,
torch, AE/AWB lock. Paquetes: `camera/`, `sensors/`, `ppg/`, `pipeline/`, `forensic/`,
`domain/`, `ui/`.

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Requiere Android SDK (`platforms;android-35`, `build-tools;35.0.0`) y JDK 17+.

### Si Gradle dice «SDK location not found»

1. Instalá **Android Studio** y en **Settings → Android SDK** copiá la ruta *Android SDK Location*.
2. En la raíz del repo, creá **`local.properties`** (no se sube a git; figura en `.gitignore`).
3. Partí de la plantilla: `cp local.properties.example local.properties` y reemplazá `sdk.dir` por tu ruta.
4. En **Windows**, escapá las barras en `sdk.dir`, p. ej. `C\:\\Users\\...\\Android\\Sdk`.
5. O definí la variable de entorno **`ANDROID_HOME`** al directorio del SDK.

---

## Estructura

```
.
├── app/                         App Android (Kotlin + Compose + Camera2)
│   └── src/main/java/com/forensicppg/monitor/{camera,sensors,ppg,pipeline,forensic,domain,ui}
├── local.properties.example     Plantilla → copiar a local.properties con tu sdk.dir
├── vercel.json                  Despliegue web deshabilitado (ver mensaje en build)
└── README.md
```

---

## Reglas de seguridad clínica

- No se usa `Math.random`, `kotlin.random.Random`, ni valores sintéticos como resultado fisiológico.
- BPM y SpO₂ solo se exponen cuando la evidencia real es suficiente (gates del pipeline).
- SpO₂ absoluto únicamente con `CalibrationProfile` ajustado contra oxímetro de referencia (persistido en `filesDir` en Android).
- La presión arterial se entrega como cribado cualitativo cuando hay señal válida, no mmHg estimados directos desde el teléfono.
