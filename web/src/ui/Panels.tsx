import type { CSSProperties } from 'react'
import type { VitalReading } from '../ppg/types'
import { HYPERTENSION_LABEL } from '../ppg/types'
import { SignalQualityIndex, SQI_BAND_LABEL } from '../ppg/sqi'
import type { CameraCapabilitiesSnapshot } from '../camera/CameraController'

const panelStyle: CSSProperties = {
  background: '#0A1014',
  border: '1px solid rgba(34,255,170,0.55)',
  borderRadius: 6,
  padding: 10,
  color: '#e6fff4'
}

const labelStyle: CSSProperties = { fontSize: 11, fontWeight: 700, letterSpacing: 1 }

export function SignalQualityPanel({ reading }: { reading: VitalReading }) {
  const sqi = new SignalQualityIndex()
  const band = sqi.band(reading.sqi)
  const color =
    band === 'EXCELLENT'
      ? '#22FFAA'
      : band === 'GOOD'
      ? '#AAFF55'
      : band === 'DEGRADED'
      ? '#FFAA22'
      : '#FF3344'
  return (
    <div style={{ ...panelStyle, borderColor: color }}>
      <div style={{ display: 'flex', justifyContent: 'space-between' }}>
        <span style={{ ...labelStyle, color }}>SQI</span>
        <span style={{ ...labelStyle, color }}>{SQI_BAND_LABEL[band]}</span>
      </div>
      <div style={{ height: 10, background: '#1a2128', borderRadius: 3, marginTop: 6, overflow: 'hidden' }}>
        <div
          style={{
            width: `${Math.max(0, Math.min(100, reading.sqi * 100))}%`,
            height: '100%',
            background: color,
            transition: 'width 100ms linear'
          }}
        />
      </div>
      <div style={{ fontSize: 11, marginTop: 6 }}>{reading.message}</div>
    </div>
  )
}

export function CameraDiagnosticsPanel({
  caps,
  fps
}: {
  caps: CameraCapabilitiesSnapshot | null
  fps: number
}) {
  return (
    <div style={panelStyle}>
      <div style={{ ...labelStyle, color: '#22FFAA' }}>DIAGNÓSTICO CÁMARA</div>
      <div style={{ fontSize: 10, marginTop: 6, display: 'grid', gridTemplateColumns: '1fr 1fr', rowGap: 2, columnGap: 8 }}>
        <span>Cam</span>
        <span>{caps?.label ?? '—'}</span>
        <span>Dispositivo</span>
        <span>{caps?.deviceId?.slice(0, 8) ?? '—'}</span>
        <span>Resolución</span>
        <span>
          {caps ? `${caps.width}×${caps.height}` : '—'}
        </span>
        <span>Torch</span>
        <span>{caps?.torchSupported ? (caps.torchOn ? 'ON' : 'OFF') : 'NO DISP.'}</span>
        <span>Exp. mode</span>
        <span>{caps?.exposureMode ?? 'auto'}</span>
        <span>Focus mode</span>
        <span>{caps?.focusMode ?? 'auto'}</span>
        <span>FPS declarado</span>
        <span>{caps?.frameRate ?? '—'}</span>
        <span>FPS real</span>
        <span>{fps.toFixed(1)}</span>
      </div>
    </div>
  )
}

export function ArrhythmiaPanel({ reading }: { reading: VitalReading }) {
  const risk = reading.hypertensionRisk
  const color =
    risk === 'NORMOTENSE'
      ? '#22FFAA'
      : risk === 'BORDERLINE'
      ? '#FFAA22'
      : risk === 'HYPERTENSIVE_PATTERN'
      ? '#FF3344'
      : '#AAAAAA'
  return (
    <div style={{ ...panelStyle, borderColor: color }}>
      <div style={{ ...labelStyle, color: '#22FFAA' }}>CRIBADO ARRITMIA / PRESIÓN</div>
      <div style={{ display: 'flex', gap: 8, marginTop: 6, flexWrap: 'wrap' }}>
        <SmallStat label="LATIDOS" value={`${reading.beatsDetected}`} color="#22FFAA" />
        <SmallStat
          label="ANÓMALOS"
          value={`${reading.abnormalBeats}`}
          color={reading.abnormalBeats > 0 ? '#FF3344' : '#22FFAA'}
        />
        <SmallStat label="SDNN" value={`${reading.rrSdnnMs.toFixed(0)} ms`} color="#AACCEE" />
        <SmallStat label="pNN50" value={`${(reading.pnn50 * 100).toFixed(0)}%`} color="#AACCEE" />
      </div>
      <div style={{ fontSize: 12, fontWeight: 700, color, marginTop: 6 }}>
        Presión / cribado: {risk ? HYPERTENSION_LABEL[risk].label : '—'}
      </div>
      <div style={{ fontSize: 10, color }}>
        {risk ? HYPERTENSION_LABEL[risk].desc : 'Señal insuficiente para cribado'}
      </div>
    </div>
  )
}

function SmallStat({ label, value, color }: { label: string; value: string; color: string }) {
  return (
    <div
      style={{
        background: '#0f171c',
        border: `1px solid ${color}55`,
        borderRadius: 4,
        padding: '4px 6px',
        minWidth: 64
      }}
    >
      <div style={{ fontSize: 9, color, opacity: 0.8 }}>{label}</div>
      <div style={{ fontSize: 14, fontWeight: 700, color: '#fff' }}>{value}</div>
    </div>
  )
}
