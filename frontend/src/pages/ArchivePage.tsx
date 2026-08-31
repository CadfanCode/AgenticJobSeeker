import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { fetchArchive } from '../api/archiveClient'
import type { ArchiveSummary } from '../archiveTypes'
import type { Page } from '../types'

export function ArchivePage() {
  const [page, setPage] = useState(0)
  const [data, setData] = useState<Page<ArchiveSummary> | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    fetchArchive(page).then(setData).catch((e: Error) => setError(e.message))
  }, [page])

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

      {data && data.totalElements > 0 && (
        <p className="mt-4 text-sm text-slate-500">
          {data.totalElements} archived{data.totalPages > 1 ? ` · page ${data.page + 1} of ${data.totalPages}` : ''}
        </p>
      )}

      <ul className="mt-2 divide-y divide-slate-200">
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

      {data && data.totalPages > 1 && (
        <div className="mt-6 flex items-center justify-between text-sm">
          <button
            disabled={data.page === 0}
            onClick={() => setPage(data.page - 1)}
            className="rounded-md border border-slate-300 px-3 py-1.5 disabled:opacity-40"
          >
            Previous
          </button>
          <span className="text-slate-600">
            Page {data.page + 1} of {data.totalPages}
          </span>
          <button
            disabled={data.page + 1 >= data.totalPages}
            onClick={() => setPage(data.page + 1)}
            className="rounded-md border border-slate-300 px-3 py-1.5 disabled:opacity-40"
          >
            Next
          </button>
        </div>
      )}
    </div>
  )
}
