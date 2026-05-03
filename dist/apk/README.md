# APKs debug — Monitor PPG Forense

> **Subidos manualmente** sólo para descarga rápida desde la PR. El `.gitignore` global del repo ignora `*.apk` para builds normales; aquí se hace excepción.

## ForensicPPG-debug-v2-acquisition-fix-37b3.apk (RECOMENDADO)

- Tamaño: 9.4 MB
- SHA-256: `ff9b63ac90a8ea3ceeaf635c7c8390311d5a632c03ad5e12dd06efed4dfcc922`
- Build: debug
- versionName: `1.0.0-forensic`
- minSdk: 26 (Android 8.0+)
- targetSdk: 35 (Android 15)

Incluye:
- Detector de picos por **Slope Sum Function** (Zong 2003) con polaridad correcta (sístole = caída de luz reflejada → trabaja sobre `-filteredWave`).
- SpO₂ por ratio canónico **R/G** (no R/B).
- Provisional SpO₂ sin calibración (operador puede ver número y calibrar contra oxímetro).
- ZLO neutro por defecto (solo se aplica si lo capturás con tapón opaco).
- PI canónico `100·AC/DC` en %.
- Gates desbloqueados: el detector emite picos sin esperar al clasificador.

## ForensicPPG-debug-v1.0.0-cursor-37b3.apk

Versión anterior. Misma firma debug — se puede instalar el v2 encima sin desinstalar el v1.

## Cómo instalar

1. Descargá el `.apk` directamente desde GitHub (botón "Download raw" sobre el archivo en la PR).
2. En el teléfono, habilitá **Orígenes desconocidos** (Ajustes → Seguridad → Instalación de fuentes externas) para el navegador o gestor de archivos.
3. Abrí el `.apk` y confirmá la instalación.
4. Concedé permiso de **Cámara** al iniciar.
5. Seguí la guía única: yema del **dedo índice de la mano no dominante** cubriendo cámara + flash, presión ligera, mano y codo apoyados, antebrazo a la altura del corazón, inmóvil 3 s.

## Firma

Ambos APKs están firmados con la **debug keystore** de Android (`~/.android/debug.keystore`). NO son aptos para distribución en Play Store. Para producción hace falta `signingConfigs.release` con un keystore propio.
