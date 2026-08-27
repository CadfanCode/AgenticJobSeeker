import type { JobFilters as Filters } from '../types'

interface Props {
  value: Filters
  onChange: (next: Filters) => void
}

export function JobFilters({ value, onChange }: Props) {
  const set = (patch: Partial<Filters>) => onChange({ ...value, ...patch, page: 0 })

  return (
    <div className="mb-4 flex flex-wrap gap-3">
      <input
        type="search"
        placeholder="Search title, employer or description…"
        value={value.q ?? ''}
        onChange={(e) => set({ q: e.target.value })}
        className="min-w-64 flex-1 rounded-md border border-slate-300 px-3 py-2 text-sm outline-none focus:border-slate-900"
      />
      <input
        type="text"
        placeholder="Municipality"
        value={value.municipality ?? ''}
        onChange={(e) => set({ municipality: e.target.value })}
        className="w-44 rounded-md border border-slate-300 px-3 py-2 text-sm outline-none focus:border-slate-900"
      />
      <select
        value={value.source ?? ''}
        onChange={(e) => set({ source: e.target.value })}
        className="rounded-md border border-slate-300 px-3 py-2 text-sm outline-none focus:border-slate-900"
      >
        <option value="">All sources</option>
        <option value="JOBTECH">JobTech</option>
        <option value="TEAMTAILOR">Teamtailor</option>
        <option value="VARBI">Varbi</option>
      </select>
    </div>
  )
}
