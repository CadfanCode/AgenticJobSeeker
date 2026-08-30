import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { fetchJobs } from '../api/client'
import { runPrescreen } from '../api/fitClient'
import { GateBadge } from '../components/GateBadge'
import { JobFilters } from '../components/JobFilters'
import { SkillMatchChip } from '../components/SkillMatchChip'
import { SourceBadge } from '../components/SourceBadge'
import { StatsHeader } from '../components/StatsHeader'
import { TriageButtons } from '../components/TriageButtons'
import type { JobFilters as Filters, JobSummary, Page } from '../types'

export function JobList() {
  const [filters, setFilters] = useState<Filters>({ page: 0, size: 20 })
  const [data, setData] = useState<Page<JobSummary> | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [ranking, setRanking] = useState(false)
  const [rankMessage, setRankMessage] = useState<string | null>(null)

  const load = () => {
    fetchJobs(filters)
      .then((page) => {
        setData(page)
        setError(null)
      })
      .catch((e: Error) => setError(e.message))
  }

  useEffect(load, [filters])

  const rank = async () => {
    setRanking(true)
    setRankMessage(null)
    try {
      const summary = await runPrescreen()
      setRankMessage(
        `Ranked ${summary.postings} postings — ${summary.withMatches} name at least one of your skills.`,
      )
      load()
    } catch (e) {
      setRankMessage((e as Error).message)
    } finally {
      setRanking(false)
    }
  }

  return (
    <div className="mx-auto max-w-5xl px-6 py-8">
      <nav className="mb-4 flex gap-4 text-sm">
        <span className="font-medium text-slate-900">Jobs</span>
        <Link to="/profile" className="text-slate-600 hover:underline">My CV profile</Link>
        <Link to="/applications" className="text-slate-600 hover:underline">Applications</Link>
        <Link to="/preferences" className="text-slate-600 hover:underline">Preferences</Link>
      </nav>
      <StatsHeader onIngested={load} />

      <div className="mb-4 flex items-center gap-3">
        <button
          onClick={rank}
          disabled={ranking}
          className="rounded-md border border-slate-300 px-3 py-1.5 text-sm hover:bg-slate-50 disabled:opacity-50"
        >
          {ranking ? 'Ranking…' : 'Rank against my CV'}
        </button>
        {rankMessage && <span className="text-sm text-slate-600">{rankMessage}</span>}
      </div>

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
                <div className="mt-2 flex flex-wrap items-center gap-2">
                  <SkillMatchChip matched={job.matchedSkillCount} names={job.matchedSkills} />
                  <GateBadge label="Language" verdict={job.languageGate} note={job.languageNote} />
                  <GateBadge label="Location" verdict={job.locationGate} />
                  {job.deadlinePassed && (
                    <span className="rounded bg-red-50 px-2 py-0.5 text-xs font-medium text-red-700">
                      Deadline passed
                    </span>
                  )}
                </div>
              </div>
              <div className="flex shrink-0 flex-col items-end gap-2">
                <SourceBadge label={job.atsVendor} />
                <TriageButtons jobId={job.id} state={job.triage} onChanged={load} />
              </div>
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
