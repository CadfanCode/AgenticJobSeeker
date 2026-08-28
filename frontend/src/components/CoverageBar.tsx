export function CoverageBar({ percent }: { percent: number }) {
  const tone =
    percent >= 70 ? 'bg-emerald-500' : percent >= 40 ? 'bg-amber-500' : 'bg-red-500'

  return (
    <div className="flex items-center gap-3">
      <div className="h-2 w-40 overflow-hidden rounded-full bg-slate-200">
        <div className={`h-full ${tone}`} style={{ width: `${Math.min(100, percent)}%` }} />
      </div>
      <span className="text-sm font-medium text-slate-700">{percent}% of requirements evidenced</span>
    </div>
  )
}
