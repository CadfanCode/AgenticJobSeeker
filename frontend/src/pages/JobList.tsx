import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { fetchJobs } from '../api/client'
import { JobFilters } from '../components/JobFilters'
import { SourceBadge } from '../components/SourceBadge'
import { StatsHeader } from '../components/StatsHeader'
import type { JobFilters as Filters, JobSummary, Page } from '../types'

export function JobList() {
  const [filters, setFilters] = useState<Filters>({ page: 0, size: 20 })
  const [data, setData] = useState<Page<JobSummary> | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = () => {
    fetchJobs(filters)
      .then((page) => {
        setData(page)
        setError(null)
      })
      .catch((e: Error) => setError(e.message))
  }

  useEffect(load, [filters])

  return (
    <div className="mx-auto max-w-5xl px-6 py-8">
      <StatsHeader onIngested={load} />
      <JobFilters value={filters} onChange={setFilters} />

      {error && (
        <p className="rounded-md bg-red-50 p-3 text-sm text-red-700">Failed to load: {error}</p>
      )}

      {data && data.content.length === 0 && (
        <p className="py-12 text-center text-sm text-slate-500">
          No jobs yet. Run an ingest to pull postings from JobTech.
        </p>
      )}

      <ul className="divide-y divide-slate-200">
        {data?.content.map((job) => (
          <li key={job.id} className="py-4">
            <div className="flex items-start justify-between gap-4">
              <div className="min-w-0">
                <Link
                  to={`/jobs/${job.id}`}
                  className="text-base font-medium text-slate-900 hover:underline"
                >
                  {job.title}
                </Link>
                <p className="mt-0.5 truncate text-sm text-slate-600">
                  {job.employerName ?? 'Unknown employer'}
                  {job.municipality ? ` · ${job.municipality}` : ''}
                </p>
              </div>
              <SourceBadge label={job.atsVendor} />
            </div>
          </li>
        ))}
      </ul>

      {data && data.totalPages > 1 && (
        <div className="mt-6 flex items-center justify-between text-sm">
          <button
            disabled={data.page === 0}
            onClick={() => setFilters({ ...filters, page: data.page - 1 })}
            className="rounded-md border border-slate-300 px-3 py-1.5 disabled:opacity-40"
          >
            Previous
          </button>
          <span className="text-slate-600">
            Page {data.page + 1} of {data.totalPages} · {data.totalElements} jobs
          </span>
          <button
            disabled={data.page + 1 >= data.totalPages}
            onClick={() => setFilters({ ...filters, page: data.page + 1 })}
            className="rounded-md border border-slate-300 px-3 py-1.5 disabled:opacity-40"
          >
            Next
          </button>
        </div>
      )}
    </div>
  )
}
