import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { fetchJob } from '../api/client'
import { SourceBadge } from '../components/SourceBadge'
import type { JobDetail as Job } from '../types'

export function JobDetail() {
  const { id } = useParams<{ id: string }>()
  const [job, setJob] = useState<Job | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!id) return
    fetchJob(Number(id))
      .then((j) => {
        setJob(j)
        setError(null)
      })
      .catch((e: Error) => setError(e.message))
  }, [id])

  if (error) {
    return (
      <p className="mx-auto max-w-3xl px-6 py-8 text-sm text-red-700">Failed to load: {error}</p>
    )
  }
  if (!job) {
    return <p className="mx-auto max-w-3xl px-6 py-8 text-sm text-slate-500">Loading…</p>
  }

  return (
    <div className="mx-auto max-w-3xl px-6 py-8">
      <Link to="/" className="text-sm text-slate-600 hover:underline">
        ← Back to jobs
      </Link>

      <h1 className="mt-4 text-2xl font-semibold tracking-tight text-slate-900">{job.title}</h1>
      <p className="mt-1 text-sm text-slate-600">
        {job.employerName ?? 'Unknown employer'}
        {job.municipality ? ` · ${job.municipality}` : ''}
      </p>

      <div className="mt-3 flex flex-wrap items-center gap-2">
        <SourceBadge label={job.atsVendor} />
        {job.sources.map((s) => (
          <SourceBadge key={`badge-${s.source}-${s.sourceAdId}`} label={s.source} />
        ))}
      </div>

      {job.applyUrl && (
        <a
          href={job.applyUrl}
          target="_blank"
          rel="noreferrer"
          className="mt-5 inline-block rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white transition hover:bg-slate-700"
        >
          Apply on employer site
        </a>
      )}

      <section className="mt-8">
        <h2 className="mb-2 text-sm font-semibold uppercase tracking-wide text-slate-500">
          Description
        </h2>
        <p className="whitespace-pre-wrap text-sm leading-relaxed text-slate-800">
          {job.description ?? 'No description captured.'}
        </p>
      </section>

      <section className="mt-8">
        <h2 className="mb-2 text-sm font-semibold uppercase tracking-wide text-slate-500">
          Seen in {job.sources.length} source{job.sources.length === 1 ? '' : 's'}
        </h2>
        <ul className="space-y-2 text-sm">
          {job.sources.map((s) => (
            <li key={`${s.source}-${s.sourceAdId}`} className="flex items-center gap-2">
              <SourceBadge label={s.source} />
              {s.sourceUrl ? (
                <a
                  href={s.sourceUrl}
                  target="_blank"
                  rel="noreferrer"
                  className="truncate text-slate-600 hover:underline"
                >
                  {s.sourceUrl}
                </a>
              ) : (
                <span className="text-slate-500">{s.sourceAdId}</span>
              )}
            </li>
          ))}
        </ul>
      </section>
    </div>
  )
}
