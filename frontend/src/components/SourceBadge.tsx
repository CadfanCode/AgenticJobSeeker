const TONE: Record<string, string> = {
  JOBTECH: 'bg-blue-100 text-blue-800 ring-blue-600/20',
  TEAMTAILOR: 'bg-emerald-100 text-emerald-800 ring-emerald-600/20',
  VARBI: 'bg-amber-100 text-amber-800 ring-amber-600/20',
}

export function SourceBadge({ label }: { label: string }) {
  const tone = TONE[label] ?? 'bg-slate-100 text-slate-700 ring-slate-500/20'
  return (
    <span
      className={`inline-flex shrink-0 items-center rounded-md px-2 py-0.5 text-xs font-medium ring-1 ring-inset ${tone}`}
    >
      {label}
    </span>
  )
}
