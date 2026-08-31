import type { GateVerdict } from '../fitTypes'

interface Props {
  label: string
  verdict: GateVerdict | null
  note?: string | null
}

const STYLES: Record<GateVerdict, string> = {
  PASS: 'bg-emerald-50 text-emerald-700',
  FLAG: 'bg-amber-50 text-amber-800',
  FAIL: 'bg-red-50 text-red-700',
  UNKNOWN: 'bg-slate-100 text-slate-500',
}

/**
 * PASS is deliberately not rendered: a list of green ticks buries the two verdicts that
 * actually need reading. UNKNOWN is shown, because "could not tell" is information —
 * UNKNOWN is not a pass.
 */
export function GateBadge({ label, verdict, note }: Props) {
  if (verdict === null || verdict === 'PASS') return null

  const title =
    verdict === 'UNKNOWN'
      ? `${label}: nothing recognised in this ad — not a pass`
      : note ?? label

  return (
    <span title={title} className={`rounded px-2 py-0.5 text-xs font-medium ${STYLES[verdict]}`}>
      {label} {verdict}
    </span>
  )
}
