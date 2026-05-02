# Monitor PPG Forense

Proyecto doble de medición PPG con dedo sobre cámara + flash:

- **`app/`** — App Android nativa (Kotlin + Jetpack Compose + Camera2).
- **`web/`** — PWA (Vite + React + TypeScript) deployable en Vercel.

Ambas comparten la misma filosofía clínica: **cero simulación**, **SpO₂ solo
con calibración persistida**, **cribado cualitativo de hipertensión** en lugar
de mmHg estimados, **visualización full-screen tipo monitor médico/forense**.

---

## Versión web (Vercel) — `/web`

PWA en React + Vite + TypeScript. Usa `getUserMedia({ facingMode: 'environment' })`
y activa el torch del celular con `MediaStreamTrack.applyConstraints({ advanced: [{ torch: true }] })`
cuando el dispositivo lo expone (Chrome Android). El pipeline DSP corre en el
main thread con `requestAnimationFrame`: detrender → Butterworth bandpass
0.5-4 Hz → SQI → Elgendi + detector derivativo → clasificador de latidos +
screening de arritmia + fusión RR/espectral + SpO₂ con calibración.

```bash
cd web
npm install
npm run build        # genera web/dist/
npm run dev          # dev server HTTPS (usar ngrok/http-server para HTTPS si probás torch)
npm test             # 26 tests unitarios (vitest)
```

### Deploy en Vercel

Hay un `vercel.json` en la **raíz del repo** que ya apunta a la subcarpeta:

```json
{ "buildCommand": "cd web && npm install && npm run build",
  "outputDirectory": "web/dist" }
```

Por lo tanto, al conectar este repo en Vercel funciona sin ajustes:

1. Importar el repositorio en [vercel.com/new](https://vercel.com/new).
2. Dejar framework en "Other" (el `vercel.json` del root ya configura todo).
3. Deploy.

También podés deployar directamente con la CLI:

```bash
npm i -g vercel
vercel --prod
```

**Importante**: el navegador sólo da acceso a cámara y torch bajo **HTTPS**
(o `localhost`). Vercel te lo da automáticamente. En prototipo local usá
`vite --host` + un túnel HTTPS (p. ej. `ngrok http 5173`) para probar desde
un celular real.

---

## Versión Android nativa — `/app`

Proyecto Kotlin + Compose + Camera2 con control manual de exposición, ISO,
frame duration, torch, AE/AWB lock. Arquitectura en 7 paquetes
(`camera/`, `sensors/`, `ppg/`, `pipeline/`, `forensic/`, `domain/`, `ui/`)
y 27 tests unitarios del DSP (JUnit).

```bash
./gradlew :app:assembleDebug        # APK debug
./gradlew :app:testDebugUnitTest    # tests unitarios
```

Requiere Android SDK (`platforms;android-35`, `build-tools;35.0.0`) y JDK 17+.

## Estructura

```
.
├── app/                         Android nativa (Kotlin + Compose + Camera2)
│   └── src/main/java/com/forensicppg/monitor/{camera,sensors,ppg,pipeline,forensic,domain,ui}
├── web/                         PWA (Vite + React + TypeScript)
│   └── src/{ppg,camera,sensors,ui,hooks}
├── vercel.json                  Config de deploy root
└── README.md
```

## Reglas de seguridad clínica compartidas

- Nunca se usa `Math.random`, `kotlin.random.Random`, ni valores sintéticos
  como resultado fisiológico.
- BPM y SpO₂ sólo se exponen cuando la evidencia real es suficiente (contacto
  del dedo, perfusión, no clipping, no movimiento, FPS estable, SQI OK).
- SpO₂ absoluto únicamente si existe un `CalibrationProfile` ajustado con
  oxímetro de referencia. Se persiste en `localStorage` (web) o `filesDir`
  (Android).
- La presión arterial se entrega como **cribado** cualitativo (`NORMOTENSO` /
  `LIMÍTROFE` / `PATRÓN HIPERTENSIVO` / `INDETERMINADO`) siguiendo la
  recomendación clínica de priorizar detección temprana de hipertensión sobre
  estimación de mmHg con teléfono.
- Cuando la señal es mala el monitor muestra `SIN CONTACTO`, `CONTACTO PARCIAL`,
  `MOVIMIENTO EXCESIVO`, `SATURACIÓN ÓPTICA`, `BAJA PERFUSIÓN` o `CALIBRACIÓN
  REQUERIDA`, nunca un número cosmético.
