import { useState } from 'react'
import type { MonitorApi } from '../hooks/useMonitor'

export function CalibrationOverlay({ monitor, onClose }: { monitor: MonitorApi; onClose: () => void }) {
  const [ref, setRef] = useState('')
  return (
    <div
      style={{
        position: 'fixed',
        inset: 0,
        background: 'rgba(0,0,0,0.95)',
        color: '#fff',
        padding: 16,
        overflow: 'auto',
        zIndex: 10,
        display: 'flex',
        flexDirection: 'column',
        gap: 8
      }}
    >
      <div style={{ color: '#22FFAA', fontSize: 18, fontWeight: 700 }}>CALIBRACIÓN FORENSE SpO₂</div>
      <div style={{ fontSize: 12 }}>
        Para mostrar un valor absoluto de SpO₂ este dispositivo debe calibrarse contra un oxímetro de
        referencia. Coloque ambos dedos en simultáneo y capture al menos 3 puntos con distintas
        saturaciones. Sin perfil válido el monitor muestra "SpO₂ requiere calibración" — nunca un
        número por default.
      </div>
      <div style={{ fontSize: 12 }}>
        Perfil activo:{' '}
        <span style={{ color: monitor.calibration ? '#22FFAA' : '#FFAA22' }}>
          {monitor.calibration?.profileId ?? 'Ninguno'}
        </span>
      </div>
      <div style={{ fontSize: 12 }}>
        Estado: <strong>{monitor.reading.state}</strong>
        <div>SQI: {monitor.reading.sqi.toFixed(2)}</div>
        <div>PI: {monitor.reading.perfusionIndex.toFixed(2)}</div>
        <div>Movimiento: {monitor.reading.motionScore.toFixed(2)}</div>
        <div>
          Ratio R ={' '}
          {monitor.lastRatioOfRatios !== null
            ? monitor.lastRatioOfRatios.toFixed(3)
            : '—'}
        </div>
      </div>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
        <input
          inputMode="decimal"
          placeholder="SpO₂ ref (oxímetro)"
          value={ref}
          onChange={(e) => setRef(e.target.value.replace(/[^\d.]/g, ''))}
          style={{
            background: '#0a1014',
            color: '#fff',
            border: '1px solid #22FFAA55',
            borderRadius: 4,
            padding: '6px 8px',
            fontFamily: 'inherit',
            flex: 1
          }}
        />
        <button
          onClick={() => {
            const v = parseFloat(ref)
            if (!Number.isNaN(v) && v >= 70 && v <= 100) monitor.captureCalibrationPoint(v)
          }}
          style={btn('#22FFAA', '#000')}
        >
          Capturar ({monitor.pendingCalibrationPoints})
        </button>
      </div>
      <div style={{ display: 'flex', gap: 8 }}>
        <button onClick={() => monitor.applyCalibration()} style={btn('#FFAA22', '#000')}>
          Guardar perfil
        </button>
        <button onClick={() => monitor.clearCalibration()} style={btn('#333844', '#fff')}>
          Descartar
        </button>
        <button onClick={onClose} style={btn('#4488CC', '#fff')}>
          Cerrar
        </button>
      </div>
      <div style={{ fontSize: 10, color: '#ccffee' }}>
        La app jamás mostrará SpO₂ absoluto sin un perfil validado. Si un punto se captura con
        SQI&nbsp;&lt;&nbsp;0.55, movimiento alto o perfusión baja, será rechazado.
      </div>
    </div>
  )
}

function btn(bg: string, fg: string): React.CSSProperties {
  return { background: bg, color: fg, border: 'none', borderRadius: 6, padding: '8px 12px', fontFamily: 'inherit', fontWeight: 700 }
}
