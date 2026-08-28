import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { fetchApplications } from '../api/applicationClient'
import type { ApplicationSummary } from '../applicationTypes'
import type { Page } from '../types'

const STATUS_TONE: Record<string, string> = {
  DRAFT: 'bg-slate-100 text-slate-700',
  APPROVED: 'bg-emerald-100 text-emerald-800',
  DISCARDED: 'bg-slate-100 text-slate-500',
  GENERATION_FAILED: 'bg-red-100 text-red-800',
}

export function ApplicationQueue() {
  const [data, setData] = useState<Page<ApplicationSummary> | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    fetchApplications().then(setData).catch((e: Error) => setError(e.message))
  }, [])

  return (
    <div className="mx-auto max-w-5xl px-6 py-8">
      <nav className="mb-4 flex gap-4 text-sm">
        <Link to="/" className="text-slate-600 hover:underline">Jobs</Link>
        <Link to="/profile" className="text-slate-600 hover:underline">My CV profile</Link>
        <span className="font-medium text-slate-900">Applications</span>
      </nav>

      <h1 className="text-2xl font-semibold tracking-tight text-slate-900">Applications</h1>
      <p className="mt-1 mb-6 text-sm text-slate-600">
        Every line of these is assembled from sentences you wrote.
      </p>

      {error && <p className="rounded-md bg-red-50 p-3 text-sm text-red-700">{error}</p>}

      {data && data.content.length === 0 && (
        <p className="py-12 text-center text-sm text-slate-500">
          No applications yet. Open a job and choose “Tailor for this job”.
        </p>
      )}

      <ul className="divide-y divide-slate-200">
        {data?.content.map((a) => (
          <li key={a.id} className="flex items-center justify-between gap-4 py-4">
            <div className="min-w-0">
              <Link to={`/applications/${a.id}`} className="font-medium text-slate-900 hover:underline">
                {a.jobTitle}
              </Link>
              <p className="mt-0.5 truncate text-sm text-slate-600">{a.employerName ?? 'Unknown employer'}</p>
            </div>
            <div className="flex shrink-0 items-center gap-3">
              <span className="text-sm text-slate-600">{a.coveragePercent}%</span>
              <span className={`rounded-md px-2 py-0.5 text-xs font-medium ${STATUS_TONE[a.status] ?? ''}`}>
                {a.status}
              </span>
            </div>
          </li>
        ))}
      </ul>
    </div>
  )
}
