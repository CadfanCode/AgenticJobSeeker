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
      <select
        value={value.sort ?? ''}
        onChange={(e) => set({ sort: e.target.value })}
        className="rounded-md border border-slate-300 px-3 py-2 text-sm outline-none focus:border-slate-900"
      >
        <option value="">Newest first</option>
        <option value="skills">Most skills matched</option>
      </select>
      <select
        value={value.triage ?? ''}
        onChange={(e) => set({ triage: e.target.value })}
        className="rounded-md border border-slate-300 px-3 py-2 text-sm outline-none focus:border-slate-900"
      >
        <option value="">Undecided and shortlisted</option>
        <option value="SHORTLISTED">Shortlisted only</option>
        <option value="DISMISSED">Dismissed only</option>
      </select>
      <label className="flex items-center gap-2 text-sm text-slate-600">
        <input
          type="checkbox"
          checked={value.includeGateFailures ?? false}
          onChange={(e) => set({ includeGateFailures: e.target.checked })}
        />
        Show vetoed
      </label>
    </div>
  )
}
