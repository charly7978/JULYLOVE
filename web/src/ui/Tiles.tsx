import type { CSSProperties } from 'react'

const tileStyle: CSSProperties = {
  background: '#0A1014',
  border: '1px solid rgba(34,255,170,0.55)',
  borderRadius: 6,
  padding: '8px 10px',
  flex: 1,
  display: 'flex',
  flexDirection: 'column',
  minWidth: 0
}

export function VitalTile({
  label,
  value,
  unit,
  color = '#22FFAA',
  subtitle
}: {
  label: string
  value: string
  unit?: string
  color?: string
  subtitle?: string | null
}) {
  return (
    <div style={{ ...tileStyle, borderColor: hexWithAlpha(color, 0.55) }}>
      <div style={{ color, fontSize: 11, fontWeight: 700, letterSpacing: 1 }}>{label}</div>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 6 }}>
        <div style={{ color: '#fff', fontSize: 28, fontWeight: 900, lineHeight: 1 }}>{value}</div>
        {unit ? <div style={{ color: hexWithAlpha(color, 0.85), fontSize: 12 }}>{unit}</div> : null}
      </div>
      {subtitle ? <div style={{ color: hexWithAlpha(color, 0.6), fontSize: 10 }}>{subtitle}</div> : null}
    </div>
  )
}

function hexWithAlpha(hex: string, alpha: number): string {
  if (hex.startsWith('#') && hex.length === 7) {
    const r = parseInt(hex.slice(1, 3), 16)
    const g = parseInt(hex.slice(3, 5), 16)
    const b = parseInt(hex.slice(5, 7), 16)
    return `rgba(${r},${g},${b},${alpha})`
  }
  return hex
}
