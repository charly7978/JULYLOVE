import { useEffect, useState } from 'react'
import type { MonitorApi } from '../hooks/useMonitor'
import { MEASUREMENT_STATE_LABEL, stateAllowsMetrics, HYPERTENSION_LABEL } from '../ppg/types'
import { SignalQualityIndex, SQI_BAND_LABEL } from '../ppg/sqi'
import { CalibrationOverlay } from './CalibrationOverlay'
import { PpgWaveform } from './PpgWaveform'

/**
 * Pantalla principal del monitor cardíaco. La onda PPG es la herramienta
 * central y ocupa el grueso del viewport. La información vital se muestra
 * como HUD sobrepuesto (arriba-izquierda, arriba-derecha, esquinas) + una
 * banda inferior con métricas secundarias. El layout usa `100dvh` y
 * safe-area-insets para ocupar el 100% de pantallas móviles.
 */
export function MonitorScreen({ monitor }: { monitor: MonitorApi }) {
  const [showCalibration, setShowCalibration] = useState(false)
  const r = monitor.reading
  const measuring = stateAllowsMetrics(r.state)
  const bpm = r.bpm !== null && measuring ? r.bpm.toFixed(0) : '--'
  const spo2 = r.spo2 !== null && measuring ? r.spo2.toFixed(1) : '--'
  const rr = r.rrMs !== null && measuring ? r.rrMs.toFixed(0) : '--'
  const showWave =
    monitor.running &&
    (r.state === 'WARMUP' || measuring) &&
    monitor.samplesRef.current.length > 1
  const stateColor = colorOfState(r.state)
  const sqiBand = new SignalQualityIndex().band(r.sqi)
  const sqiColor =
    sqiBand === 'EXCELLENT'
      ? '#22FFAA'
      : sqiBand === 'GOOD'
      ? '#AAFF55'
      : sqiBand === 'DEGRADED'
      ? '#FFAA22'
      : '#FF3344'
  const spo2Label =
    r.spo2 !== null
      ? monitor.calibration
        ? 'clínico'
        : 'provisional'
      : r.state === 'MEASURING'
      ? 'req. datos'
      : '—'
  const risk = r.hypertensionRisk

  useEffect(() => {
    if (!monitor.running) return
    const el = document.documentElement
    if (document.fullscreenElement) return
    el.requestFullscreen?.({ navigationUI: 'hide' }).catch(() => void 0)
    const anyScreen = window.screen as unknown as { orientation?: { lock?: (t: string) => Promise<void> } }
    anyScreen.orientation?.lock?.('portrait').catch(() => void 0)
  }, [monitor.running])

  return (
    <div style={rootStyle}>
      {/* La onda ocupa toda la pantalla */}
      <div style={waveContainer}>
        <PpgWaveform samplesRef={monitor.samplesRef} beatsRef={monitor.beatsRef} windowSeconds={8} />
        {!showWave ? (
          <div style={overlayCenter}>
            <div style={{ ...overlayTitle, color: stateColor }}>{MEASUREMENT_STATE_LABEL[r.state]}</div>
            <div style={overlayMsg}>{r.message}</div>
          </div>
        ) : null}

        {/* HUD superior */}
        <div style={hudTop}>
          <div>
            <div style={hudLabel}>MONITOR PPG FORENSE</div>
            <div style={{ ...hudStateLabel, color: stateColor }}>{MEASUREMENT_STATE_LABEL[r.state]}</div>
          </div>
          <div style={{ flex: 1 }} />
          <div style={{ textAlign: 'right' }}>
            <div style={hudLabel}>FPS · SQI</div>
            <div style={hudStateLabel}>
              <span style={{ color: '#AACCEE' }}>{monitor.fps.toFixed(0)}</span>
              {'  '}·{'  '}
              <span style={{ color: sqiColor }}>{SQI_BAND_LABEL[sqiBand]}</span>
            </div>
          </div>
        </div>

        {/* HUD BPM grande (centrado arriba) */}
        <div style={hudBpm}>
          <div style={{ color: '#22FFAA', fontSize: 11, letterSpacing: 3, fontWeight: 800 }}>
            FRECUENCIA CARDÍACA
          </div>
          <div style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
            <div
              style={{
                color: r.bpm !== null ? '#22FFAA' : '#556677',
                fontSize: 'clamp(56px, 14vw, 140px)',
                fontWeight: 900,
                lineHeight: 0.95,
                letterSpacing: 1,
                textShadow: r.bpm !== null ? '0 0 14px rgba(34,255,170,0.55)' : 'none'
              }}
            >
              {bpm}
            </div>
            <div style={{ color: '#8fffd0', fontSize: 'clamp(12px, 2.5vw, 20px)', fontWeight: 700 }}>
              lpm
            </div>
          </div>
          {r.bpm !== null ? (
            <div style={{ color: '#66ccaa', fontSize: 11 }}>
              confianza {(r.bpmConfidence * 100).toFixed(0)}% · {r.beatsDetected} latidos
            </div>
          ) : null}
        </div>

        {/* HUD SpO2 grande (derecha arriba) */}
        <div style={hudSpo2}>
          <div style={{ color: '#22FFAA', fontSize: 10, letterSpacing: 2, fontWeight: 700 }}>SpO₂</div>
          <div style={{ display: 'flex', alignItems: 'baseline', gap: 4 }}>
            <div
              style={{
                color: r.spo2 !== null ? (monitor.calibration ? '#22FFAA' : '#FFDD55') : '#556677',
                fontSize: 'clamp(30px, 8vw, 64px)',
                fontWeight: 900,
                lineHeight: 0.95
              }}
            >
              {spo2}
            </div>
            <div style={{ color: '#8fffd0', fontSize: 'clamp(10px, 2vw, 14px)' }}>%</div>
          </div>
          <div style={{ color: '#88aaaa', fontSize: 10 }}>{spo2Label}</div>
        </div>

        {/* HUD inferior con métricas */}
        <div style={hudBottom}>
          <Stat label="RR" value={rr} unit="ms" color="#AACCEE" />
          <Stat label="PI" value={measuring ? r.perfusionIndex.toFixed(2) : '--'} unit="%" color="#AACCEE" />
          <Stat
            label="SDNN"
            value={r.rrSdnnMs !== null ? r.rrSdnnMs.toFixed(0) : '--'}
            unit="ms"
            color="#88ccdd"
          />
          <Stat
            label="ANOMALÍAS"
            value={`${r.abnormalBeats}`}
            color={r.abnormalBeats > 0 ? '#FF3344' : '#88ccdd'}
          />
          <Stat label="MOV." value={r.motionScore.toFixed(2)} color="#AACCEE" />
          <Stat
            label="CRIBADO"
            value={risk ? HYPERTENSION_LABEL[risk].label : '—'}
            color={
              risk === 'HYPERTENSIVE_PATTERN'
                ? '#FF3344'
                : risk === 'BORDERLINE'
                ? '#FFAA22'
                : risk === 'NORMOTENSE'
                ? '#22FFAA'
                : '#88ccdd'
            }
          />
        </div>

        {/* Barra SQI estrecha */}
        <div style={sqiBar}>
          <div
            style={{
              height: '100%',
              width: `${Math.max(0, Math.min(100, r.sqi * 100))}%`,
              background: sqiColor,
              transition: 'width 100ms linear'
            }}
          />
        </div>

        {/* Mensaje clínico */}
        <div style={clinicalMsg}>{r.message}</div>
      </div>

      {/* Controles al pie */}
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

function Stat({
  label,
  value,
  unit,
  color
}: {
  label: string
  value: string
  unit?: string
  color: string
}) {
  return (
    <div
      style={{
        background: 'rgba(3,8,10,0.72)',
        border: `1px solid ${color}66`,
        borderRadius: 4,
        padding: '4px 8px',
        display: 'flex',
        flexDirection: 'column',
        minWidth: 0,
        flex: 1
      }}
    >
      <div style={{ fontSize: 9, color: `${color}cc`, letterSpacing: 1 }}>{label}</div>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 3 }}>
        <span style={{ color: '#fff', fontSize: 14, fontWeight: 800 }}>{value}</span>
        {unit ? <span style={{ color: `${color}aa`, fontSize: 10 }}>{unit}</span> : null}
      </div>
    </div>
  )
}

function colorOfState(state: string): string {
  switch (state) {
    case 'MEASURING': return '#22FFAA'
    case 'WARMUP': return '#FFDD22'
    case 'DEGRADED':
    case 'CONTACT_PARTIAL':
    case 'CALIBRATION_REQUIRED': return '#FFAA22'
    case 'NO_CONTACT': return '#8899AA'
    default: return '#FF3344'
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
  background: '#020608',
  color: '#fff',
  fontFamily: 'inherit',
  overflow: 'hidden',
  paddingTop: 'env(safe-area-inset-top)',
  paddingBottom: 'env(safe-area-inset-bottom)'
}

const waveContainer: React.CSSProperties = {
  position: 'relative',
  flex: 1,
  minHeight: 0,
  overflow: 'hidden'
}

const overlayCenter: React.CSSProperties = {
  position: 'absolute',
  inset: 0,
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'center',
  justifyContent: 'center',
  background: 'rgba(2,6,10,0.55)',
  pointerEvents: 'none',
  gap: 6,
  zIndex: 3
}

const overlayTitle: React.CSSProperties = {
  fontWeight: 900,
  fontSize: 'clamp(20px, 6vw, 40px)',
  letterSpacing: 2,
  textShadow: '0 0 12px rgba(34,255,170,0.6)'
}

const overlayMsg: React.CSSProperties = {
  color: '#e6fff4',
  fontSize: 'clamp(12px, 2.6vw, 16px)',
  textAlign: 'center',
  padding: '0 16px'
}

const hudTop: React.CSSProperties = {
  position: 'absolute',
  top: 10,
  left: 12,
  right: 12,
  display: 'flex',
  justifyContent: 'space-between',
  alignItems: 'flex-start',
  zIndex: 2,
  pointerEvents: 'none'
}

const hudLabel: React.CSSProperties = {
  color: '#22FFAA',
  fontSize: 9,
  letterSpacing: 2,
  fontWeight: 700
}

const hudStateLabel: React.CSSProperties = {
  fontSize: 13,
  fontWeight: 800,
  letterSpacing: 1
}

const hudBpm: React.CSSProperties = {
  position: 'absolute',
  top: 'clamp(42px, 7vh, 70px)',
  left: 12,
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'flex-start',
  zIndex: 2,
  pointerEvents: 'none'
}

const hudSpo2: React.CSSProperties = {
  position: 'absolute',
  top: 'clamp(42px, 7vh, 70px)',
  right: 12,
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'flex-end',
  zIndex: 2,
  pointerEvents: 'none'
}

const hudBottom: React.CSSProperties = {
  position: 'absolute',
  left: 8,
  right: 8,
  bottom: 36,
  display: 'flex',
  gap: 4,
  zIndex: 2,
  pointerEvents: 'none',
  flexWrap: 'wrap'
}

const sqiBar: React.CSSProperties = {
  position: 'absolute',
  left: 0,
  right: 0,
  bottom: 20,
  height: 6,
  background: 'rgba(8,16,20,0.9)',
  zIndex: 2
}

const clinicalMsg: React.CSSProperties = {
  position: 'absolute',
  left: 0,
  right: 0,
  bottom: 0,
  background: 'rgba(3,8,10,0.78)',
  color: '#aaccee',
  fontSize: 11,
  padding: '3px 8px',
  textAlign: 'center',
  zIndex: 2
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
    samples: monitor.samplesRef.current.slice(-600),
    beats: monitor.beatsRef.current.slice(),
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
