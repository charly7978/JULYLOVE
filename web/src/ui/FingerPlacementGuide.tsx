import type { CSSProperties } from 'react'

export interface FingerPlacementGuideProps {
  onContinue: () => void
}

/**
 * Guía única y estandarizada de toma de muestra PPG por cámara + flash.
 *
 * Esta es la ÚNICA posición que la app guía. Está basada en:
 *
 *  - Mather et al. 2024 (Front. Digital Health, scoping review FC-PPG vs
 *    ECG, doi:10.3389/fdgth.2024.1326511): pulpejo distal sobre flash +
 *    lente del mismo dispositivo, presión moderada, brazo apoyado.
 *  - Pelegris et al. 2010, Scully et al. 2012 (HR/SpO₂ con cámara visible):
 *    cobertura completa del LED y el sensor por la misma yema.
 *  - Wieringa 2005, Lamonaca 2017 y Wang/Xuan 2023 (calibración cPPG con
 *    smartphone): inmovilidad de mano y brazo, fuente de luz fija para
 *    bloquear AE/AWB sin variar entre frames.
 *
 *  Posición:
 *    1. Dedo ÍNDICE de la mano NO dominante.
 *    2. Yema (pulpejo distal) cubriendo COMPLETAMENTE la cámara trasera y
 *       el LED de flash, los dos al mismo tiempo, sin huecos por donde
 *       entre luz ambiental.
 *    3. Presión LIGERA: la suficiente para sellar luz ambiente pero sin
 *       blanquear la piel (eso vacía el lecho capilar y mata el pulso).
 *    4. Mano apoyada sobre superficie firme. Codo apoyado. Antebrazo a
 *       la altura del corazón.
 *    5. Inmóvil 3 segundos antes de iniciar y durante toda la captura.
 *    6. No hablar; respirar normal.
 */
export function FingerPlacementGuide({ onContinue }: FingerPlacementGuideProps) {
  return (
    <div style={overlayStyle} role="dialog" aria-label="Guía de colocación del dedo">
      <div style={cardStyle}>
        <div style={titleStyle}>POSICIÓN ÚNICA DE TOMA DE MUESTRA</div>
        <div style={subtitleStyle}>
          Una sola forma correcta. No hay otras configuraciones ni elección de dedo.
        </div>

        <FingerDiagram />

        <ol style={listStyle}>
          <li>
            <strong>Dedo ÍNDICE</strong> de la mano <strong>no dominante</strong>.
          </li>
          <li>
            La <strong>yema</strong> (pulpejo distal) cubre <strong>al mismo tiempo la
            cámara trasera y el LED del flash</strong>, sin dejar huecos por donde
            entre luz ambiente.
          </li>
          <li>
            <strong>Presión ligera</strong>. La piel no debe blanquearse: si se
            blanquea, vacía el lecho capilar y la onda desaparece.
          </li>
          <li>
            <strong>Apoye la mano</strong> sobre una superficie firme. Codo apoyado.
            Antebrazo a la altura del corazón.
          </li>
          <li>
            Quédese <strong>inmóvil 3 segundos</strong> antes de iniciar y durante
            toda la captura. No hable. Respire normalmente.
          </li>
        </ol>

        <div style={notesStyle}>
          La onda y el BPM se publican únicamente cuando la evidencia óptica viva
          es suficiente. Si el monitor muestra <code>SIN CONTACTO</code>,
          <code> SATURACIÓN ÓPTICA</code> o <code>BAJA PERFUSIÓN</code>, ajuste
          presión o cobertura. Sin valores cosméticos.
        </div>

        <button style={btnStyle} onClick={onContinue} type="button">
          ENTENDIDO — INICIAR MEDICIÓN
        </button>
      </div>
    </div>
  )
}

function FingerDiagram() {
  return (
    <svg
      viewBox="0 0 320 180"
      style={{ width: '100%', height: 'auto', maxHeight: 200, marginBottom: 12 }}
      aria-hidden="true"
    >
      <defs>
        <radialGradient id="flash" cx="50%" cy="50%" r="50%">
          <stop offset="0%" stopColor="#FFF7B0" stopOpacity={1} />
          <stop offset="60%" stopColor="#FFE05B" stopOpacity={0.4} />
          <stop offset="100%" stopColor="#FFE05B" stopOpacity={0} />
        </radialGradient>
        <linearGradient id="finger" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="#F4B8A3" />
          <stop offset="100%" stopColor="#C57C66" />
        </linearGradient>
      </defs>
      <rect x="80" y="20" width="160" height="140" rx="22" fill="#0d1014" stroke="#22FFAA" strokeWidth="1.6" />
      <circle cx="138" cy="70" r="14" fill="#0a0a0a" stroke="#22FFAA" strokeWidth="1.4" />
      <circle cx="138" cy="70" r="6" fill="#1A2530" />
      <circle cx="182" cy="70" r="10" fill="url(#flash)" />
      <circle cx="182" cy="70" r="6" fill="#FFE05B" />
      <ellipse cx="160" cy="120" rx="58" ry="38" fill="url(#finger)" opacity="0.92" />
      <path
        d="M120 110 Q160 70 200 110"
        stroke="#22FFAA"
        strokeWidth="1.6"
        fill="none"
        strokeDasharray="4 3"
      />
      <text x="160" y="172" textAnchor="middle" fontFamily="ui-monospace, monospace" fontSize="10" fill="#88ccaa">
        yema cubriendo cámara + flash
      </text>
    </svg>
  )
}

const overlayStyle: CSSProperties = {
  position: 'fixed',
  inset: 0,
  background: 'rgba(2,6,10,0.96)',
  zIndex: 20,
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  padding: 12
}

const cardStyle: CSSProperties = {
  background: '#0A1014',
  border: '1px solid rgba(34,255,170,0.55)',
  borderRadius: 10,
  padding: 18,
  maxWidth: 480,
  maxHeight: '92dvh',
  overflowY: 'auto',
  color: '#e6fff4',
  fontFamily: 'inherit',
  display: 'flex',
  flexDirection: 'column',
  gap: 8
}

const titleStyle: CSSProperties = {
  color: '#22FFAA',
  fontWeight: 900,
  letterSpacing: 1.2,
  fontSize: 16
}

const subtitleStyle: CSSProperties = {
  color: '#a4d8c4',
  fontSize: 11,
  marginBottom: 8
}

const listStyle: CSSProperties = {
  margin: '0 0 8px 18px',
  padding: 0,
  fontSize: 13,
  lineHeight: 1.45,
  color: '#dff5ec'
}

const notesStyle: CSSProperties = {
  background: '#0F171C',
  border: '1px solid #1a3340',
  borderRadius: 6,
  padding: 8,
  fontSize: 11,
  color: '#a8c8c0',
  lineHeight: 1.4
}

const btnStyle: CSSProperties = {
  marginTop: 10,
  alignSelf: 'stretch',
  background: '#22FFAA',
  color: '#000',
  border: 'none',
  borderRadius: 6,
  padding: '12px 14px',
  fontFamily: 'inherit',
  fontWeight: 900,
  letterSpacing: 1.2,
  fontSize: 13
}
