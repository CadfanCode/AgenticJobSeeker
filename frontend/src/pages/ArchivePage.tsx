import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { fetchArchive } from '../api/archiveClient'
import type { ArchiveSummary } from '../archiveTypes'
import type { Page } from '../types'

export function ArchivePage() {
  const [data, setData] = useState<Page<ArchiveSummary> | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    fetchArchive().then(setData).catch((e: Error) => setError(e.message))
  }, [])

  return (
    <div className="mx-auto max-w-4xl px-6 py-8">
      <nav className="mb-4 flex gap-4 text-sm">
        <Link to="/" className="text-slate-600 hover:underline">Jobs</Link>
        <Link to="/applications" className="text-slate-600 hover:underline">Applications</Link>
        <span className="font-medium text-slate-900">Archive</span>
      </nav>

      <h1 className="text-2xl font-semibold tracking-tight text-slate-900">Archive</h1>
      <p className="mt-1 text-sm text-slate-600">
        What you approved, exactly as it was — including the ad as it read that day. Useful when
        you need to refer back to an application before or during an interview.
      </p>

      {error && (
        <p className="mt-4 rounded-md bg-red-50 p-3 text-sm text-red-700">{error}</p>
      )}

      {data && data.content.length === 0 && (
        <p className="py-12 text-center text-sm text-slate-500">
          Nothing archived yet. Approving an application freezes it here.
        </p>
      )}

      <ul className="mt-6 divide-y divide-slate-200">
        {data?.content.map((entry) => (
          <li key={entry.id} className="py-4">
            <Link to={`/archive/${entry.id}`} className="text-base font-medium text-slate-900 hover:underline">
              {entry.jobTitle}
            </Link>
            <p className="mt-0.5 text-sm text-slate-600">
              {entry.employerName ?? 'Unknown employer'} ·{' '}
              {new Date(entry.approvedAt).toLocaleDateString()} · {entry.coveragePercent}% covered
            </p>
          </li>
        ))}
      </ul>
    </div>
  )
}
