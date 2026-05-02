import { useEffect, useState } from 'react'
import type { MonitorApi } from '../hooks/useMonitor'
import { MEASUREMENT_STATE_LABEL, stateAllowsMetrics } from '../ppg/types'
import { CalibrationOverlay } from './CalibrationOverlay'
import { ArrhythmiaPanel, CameraDiagnosticsPanel, SignalQualityPanel } from './Panels'
import { PpgWaveform } from './PpgWaveform'
import { VitalTile } from './Tiles'

export function MonitorScreen({ monitor }: { monitor: MonitorApi }) {
  const [showCalibration, setShowCalibration] = useState(false)
  const r = monitor.reading
  const measuring = stateAllowsMetrics(r.state)
  const bpm = r.bpm !== null && measuring ? r.bpm.toFixed(0) : '--'
  const spo2 = r.spo2 !== null && measuring ? r.spo2.toFixed(1) : '--'
  const rr = r.rrMs !== null && measuring ? r.rrMs.toFixed(0) : '--'
  const showWave = monitor.running && (r.state === 'WARMUP' || measuring) && monitor.samples.length > 1

  // Fullscreen automático al iniciar captura (quita las barras del browser
  // en mobile). Si el navegador rechaza, se ignora.
  useEffect(() => {
    if (!monitor.running) return
    const el = document.documentElement
    if (document.fullscreenElement) return
    el.requestFullscreen?.({ navigationUI: 'hide' }).catch(() => void 0)
    if ('screen' in window && 'orientation' in window.screen) {
      const o = window.screen.orientation as unknown as { lock?: (t: string) => Promise<void> }
      o?.lock?.('portrait').catch(() => void 0)
    }
  }, [monitor.running])

  const stateColor = colorOfState(r.state)

  return (
    <div style={rootStyle}>
      {/* Barra superior */}
      <div style={topBar}>
        <span style={{ color: '#22FFAA', fontWeight: 700, letterSpacing: 1, fontSize: 12 }}>
          MONITOR PPG FORENSE
        </span>
        <span style={{ flex: 1 }} />
        <span style={{ color: stateColor, fontWeight: 800, fontSize: 12 }}>
          {MEASUREMENT_STATE_LABEL[r.state]}
        </span>
        <span
          style={{
            color: monitor.calibration ? '#22FFAA' : '#FFAA22',
            fontSize: 10,
            fontWeight: 700
          }}
        >
          {monitor.calibration ? 'CAL OK' : 'SIN CAL'}
        </span>
        <span
          style={{
            color: monitor.running ? '#22FFAA' : '#8899AA',
            fontSize: 10,
            fontWeight: 700
          }}
        >
          {monitor.running ? 'ACT' : 'OFF'}
        </span>
      </div>

      {/* Cuerpo principal */}
      <div style={bodyStyle}>
        <div style={waveColumn}>
          <div style={{ position: 'relative', flex: 1, minHeight: 0 }}>
            {/* Zona de onda ocupa 100% */}
            <PpgWaveform samples={monitor.samples} beats={monitor.beats} />
            {!showWave ? (
              <div style={overlayCenter}>
                <div style={overlayTitle}>{MEASUREMENT_STATE_LABEL[r.state]}</div>
                <div style={overlayMsg}>{r.message}</div>
              </div>
            ) : null}
          </div>
          <SignalQualityPanel reading={r} />
        </div>
        <div style={tilesColumn}>
          <div style={{ display: 'flex', gap: 4 }}>
            <VitalTile
              label="BPM"
              value={bpm}
              unit="/min"
              color="#22FFAA"
              subtitle={
                r.bpm !== null && measuring ? `conf ${(r.bpmConfidence * 100).toFixed(0)}%` : null
              }
            />
            <VitalTile
              label="SpO₂"
              value={spo2}
              unit="%"
              color={r.spo2 !== null ? '#22FFAA' : '#FFAA22'}
              subtitle={r.spo2 === null && measuring ? 'req. calibración' : null}
            />
          </div>
          <div style={{ display: 'flex', gap: 4 }}>
            <VitalTile label="RR" value={rr} unit="ms" color="#AACCEE" />
            <VitalTile
              label="PI"
              value={measuring ? r.perfusionIndex.toFixed(2) : '--'}
              unit="%"
              color="#AACCEE"
            />
          </div>
          <div style={{ display: 'flex', gap: 4 }}>
            <VitalTile label="FPS" value={monitor.fps.toFixed(1)} color="#AACCEE" />
            <VitalTile label="MOV." value={r.motionScore.toFixed(2)} color="#AACCEE" />
          </div>
          <ArrhythmiaPanel reading={r} />
          <CameraDiagnosticsPanel caps={monitor.caps} fps={monitor.fps} />
        </div>
      </div>

      {/* Instrucciones */}
      <div style={instructions}>
        Cubrir cámara y flash con el dedo índice · No mover · Esperar a “MIDIENDO”
      </div>

      {/* Botones */}
      <div style={controlBar}>
        <button
          style={btnStyle(monitor.running ? '#FF3344' : '#22FFAA')}
          onClick={() => (monitor.running ? void monitor.stop() : void monitor.start())}
        >
          {monitor.running ? 'DETENER' : 'INICIAR'}
        </button>
        <button style={btnStyle('#FFAA22')} onClick={() => setShowCalibration(true)}>
          CALIBRAR
        </button>
        <button style={btnStyle('#4488CC', '#fff')} onClick={() => exportSessionJson(monitor)}>
          EXPORTAR
        </button>
      </div>

      {monitor.error ? (
        <div style={errorBanner}>Error: {monitor.error}</div>
      ) : null}

      {showCalibration ? (
        <CalibrationOverlay monitor={monitor} onClose={() => setShowCalibration(false)} />
      ) : null}
    </div>
  )
}

function colorOfState(state: string): string {
  switch (state) {
    case 'MEASURING':
      return '#22FFAA'
    case 'WARMUP':
      return '#FFDD22'
    case 'DEGRADED':
    case 'CONTACT_PARTIAL':
    case 'CALIBRATION_REQUIRED':
      return '#FFAA22'
    case 'NO_CONTACT':
      return '#8899AA'
    default:
      return '#FF3344'
  }
}

const rootStyle: React.CSSProperties = {
  position: 'fixed',
  top: 0,
  left: 0,
  right: 0,
  bottom: 0,
  width: '100vw',
  height: '100dvh',
  minHeight: '100dvh',
  display: 'flex',
  flexDirection: 'column',
  background: '#02070A',
  color: '#fff',
  fontFamily: 'inherit',
  overflow: 'hidden',
  paddingTop: 'env(safe-area-inset-top)',
  paddingBottom: 'env(safe-area-inset-bottom)'
}

const topBar: React.CSSProperties = {
  background: '#0A0F13',
  padding: '6px 10px',
  display: 'flex',
  alignItems: 'center',
  gap: 10,
  flexShrink: 0
}

const bodyStyle: React.CSSProperties = {
  flex: 1,
  display: 'flex',
  gap: 6,
  padding: 6,
  minHeight: 0,
  overflow: 'hidden'
}

const waveColumn: React.CSSProperties = {
  flex: 2,
  display: 'flex',
  flexDirection: 'column',
  gap: 4,
  minHeight: 0,
  minWidth: 0
}

const tilesColumn: React.CSSProperties = {
  flex: 1,
  display: 'flex',
  flexDirection: 'column',
  gap: 6,
  minWidth: 160,
  overflow: 'auto'
}

const overlayCenter: React.CSSProperties = {
  position: 'absolute',
  inset: 0,
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'center',
  justifyContent: 'center',
  background: 'rgba(3,6,9,0.45)',
  pointerEvents: 'none',
  gap: 6
}

const overlayTitle: React.CSSProperties = {
  color: '#22FFAA',
  fontWeight: 900,
  fontSize: 20,
  letterSpacing: 2,
  textShadow: '0 0 8px rgba(34,255,170,0.6)'
}

const overlayMsg: React.CSSProperties = {
  color: '#e6fff4',
  fontSize: 13,
  textAlign: 'center',
  padding: '0 16px'
}

const instructions: React.CSSProperties = {
  background: '#05090C',
  padding: '4px 10px',
  color: '#aaccee',
  fontSize: 10,
  flexShrink: 0,
  textAlign: 'center'
}

const controlBar: React.CSSProperties = {
  background: '#070B0E',
  padding: 8,
  display: 'flex',
  gap: 6,
  flexShrink: 0
}

const errorBanner: React.CSSProperties = {
  background: '#301015',
  color: '#ffaaaa',
  padding: 8,
  fontSize: 12,
  flexShrink: 0
}

function btnStyle(bg: string, fg: string = '#000'): React.CSSProperties {
  return {
    flex: 1,
    border: 'none',
    borderRadius: 6,
    padding: '12px 8px',
    background: bg,
    color: fg,
    fontFamily: 'inherit',
    fontWeight: 900,
    letterSpacing: 1,
    fontSize: 13
  }
}

async function exportSessionJson(monitor: MonitorApi) {
  const payload = {
    exportedAtMs: Date.now(),
    reading: monitor.reading,
    samples: monitor.samples.slice(-600),
    beats: monitor.beats,
    calibrationProfileId: monitor.calibration?.profileId ?? null,
    caps: monitor.caps
  }
  const text = JSON.stringify(payload, null, 2)
  const hash = await sha256Hex(text)
  const wrapped = JSON.stringify({ ...payload, integrityHash: hash }, null, 2)
  const blob = new Blob([wrapped], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `forensic-ppg-session-${Date.now()}.json`
  a.click()
  URL.revokeObjectURL(url)
}

async function sha256Hex(text: string): Promise<string> {
  const buf = new TextEncoder().encode(text)
  const digest = await crypto.subtle.digest('SHA-256', buf)
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('')
}
