import { useState } from 'react'
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
  const bpm = r.bpm !== null && measuring && r.bpmConfidence >= 0.35 ? r.bpm.toFixed(0) : '--'
  const spo2 = r.spo2 !== null && measuring ? r.spo2.toFixed(1) : '--'
  const rr = r.rrMs !== null && measuring ? r.rrMs.toFixed(0) : '--'
  const stateColor =
    r.state === 'MEASURING'
      ? '#22FFAA'
      : r.state === 'WARMUP'
      ? '#FFDD22'
      : r.state === 'DEGRADED' || r.state === 'CONTACT_PARTIAL' || r.state === 'CALIBRATION_REQUIRED'
      ? '#FFAA22'
      : r.state === 'NO_CONTACT'
      ? '#8899AA'
      : '#FF3344'

  return (
    <div
      style={{
        position: 'fixed',
        inset: 0,
        background: '#02070A',
        display: 'flex',
        flexDirection: 'column',
        color: '#fff'
      }}
    >
      {/* Barra superior */}
      <div style={{ background: '#0A0F13', padding: '6px 10px', display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
        <span style={{ color: '#22FFAA', fontWeight: 700, letterSpacing: 1 }}>MONITOR PPG FORENSE v1</span>
        <span style={{ flex: 1 }} />
        <span style={{ color: stateColor, fontWeight: 700 }}>{MEASUREMENT_STATE_LABEL[r.state]}</span>
        <span style={{ color: monitor.calibration ? '#22FFAA' : '#FFAA22' }}>
          {monitor.calibration ? 'CALIBRADO' : 'SIN CALIB. SpO₂'}
        </span>
        <span style={{ color: monitor.running ? '#22FFAA' : '#8899AA' }}>
          {monitor.running ? 'CAPTURA ACTIVA' : 'CAPTURA INACTIVA'}
        </span>
      </div>

      {/* Cuerpo principal */}
      <div style={{ flex: 1, display: 'flex', gap: 6, padding: 6, minHeight: 0 }}>
        <div style={{ flex: 2, display: 'flex', flexDirection: 'column', gap: 4, minHeight: 0 }}>
          <div style={{ flex: 1, minHeight: 0 }}>
            <PpgWaveform samples={monitor.samples} beats={monitor.beats} />
          </div>
          <SignalQualityPanel reading={r} />
        </div>
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 6, minWidth: 180 }}>
          <div style={{ display: 'flex', gap: 4 }}>
            <VitalTile
              label="BPM"
              value={bpm}
              unit="/min"
              color="#22FFAA"
              subtitle={measuring && r.bpmConfidence > 0 ? `conf ${(r.bpmConfidence * 100).toFixed(0)}%` : null}
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
            <VitalTile label="PI" value={r.perfusionIndex.toFixed(2)} unit="%" color="#AACCEE" />
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
      <div style={{ background: '#05090C', padding: '4px 10px', color: '#aaccee', fontSize: 11 }}>
        Cubrir cámara y flash con el dedo índice · No mover · Esperar a {MEASUREMENT_STATE_LABEL['MEASURING']}
      </div>

      {/* Barra de control */}
      <div style={{ background: '#070B0E', padding: 8, display: 'flex', gap: 6 }}>
        <button
          style={btnStyle(monitor.running ? '#FF3344' : '#22FFAA')}
          onClick={() => (monitor.running ? void monitor.stop() : void monitor.start())}
        >
          {monitor.running ? 'DETENER' : 'INICIAR'}
        </button>
        <button style={btnStyle('#FFAA22')} onClick={() => setShowCalibration(true)}>
          CALIBRAR
        </button>
        <button
          style={btnStyle('#4488CC', '#fff')}
          onClick={() => exportSessionJson(monitor)}
        >
          EXPORTAR
        </button>
      </div>

      {monitor.error ? (
        <div style={{ background: '#301015', color: '#ffaaaa', padding: 8, fontSize: 12 }}>
          Error: {monitor.error}
        </div>
      ) : null}

      {showCalibration ? <CalibrationOverlay monitor={monitor} onClose={() => setShowCalibration(false)} /> : null}
    </div>
  )
}

function btnStyle(bg: string, fg: string = '#000'): React.CSSProperties {
  return {
    flex: 1,
    border: 'none',
    borderRadius: 6,
    padding: '10px 8px',
    background: bg,
    color: fg,
    fontFamily: 'inherit',
    fontWeight: 900,
    letterSpacing: 1
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
