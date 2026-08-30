import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { fetchJob } from '../api/client'
import { fetchApplicationForJob, tailorJob } from '../api/applicationClient'
import { fetchDeepFit, scoreJob } from '../api/fitClient'
import type { Application } from '../applicationTypes'
import { SourceBadge } from '../components/SourceBadge'
import type { DeepFit } from '../fitTypes'
import type { JobDetail as Job } from '../types'

export function JobDetail() {
  const { id } = useParams<{ id: string }>()
  const [job, setJob] = useState<Job | null>(null)
  const [error, setError] = useState<string | null>(null)

  const navigate = useNavigate()
  const [application, setApplication] = useState<Application | null>(null)
  const [tailoring, setTailoring] = useState(false)
  const [tailorError, setTailorError] = useState<string | null>(null)

  const [fit, setFit] = useState<DeepFit | null>(null)
  const [scoring, setScoring] = useState(false)
  const [fitError, setFitError] = useState<string | null>(null)

  useEffect(() => {
    if (!id) return
    fetchJob(Number(id))
      .then((j) => {
        setJob(j)
        setError(null)
      })
      .catch((e: Error) => setError(e.message))
  }, [id])

  useEffect(() => {
    if (!id) return
    fetchApplicationForJob(Number(id)).then(setApplication).catch(() => setApplication(null))
  }, [id])

  useEffect(() => {
    if (!id) return
    fetchDeepFit(Number(id)).then(setFit).catch(() => setFit(null))
  }, [id])

  const runScore = async () => {
    if (!id) return
    setScoring(true)
    setFitError(null)
    try {
      setFit(await scoreJob(Number(id)))
    } catch (e) {
      setFitError((e as Error).message)
    } finally {
      setScoring(false)
    }
  }

  const runTailor = async () => {
    if (!id) return
    setTailoring(true)
    setTailorError(null)
    try {
      const created = await tailorJob(Number(id))
      navigate(`/applications/${created.id}`)
    } catch (e) {
      setTailorError((e as Error).message)
    } finally {
      setTailoring(false)
    }
  }

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

      {/* Only the ATS vendor here; the contributing sources are listed in full below. */}
      <div className="mt-3 flex flex-wrap items-center gap-2">
        <span className="text-xs text-slate-500">ATS</span>
        <SourceBadge label={job.atsVendor} />
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

      <div className="mt-4">
        {application ? (
          <Link
            to={`/applications/${application.id}`}
            className="inline-block rounded-md border border-slate-300 px-4 py-2 text-sm hover:bg-slate-50"
          >
            View tailored application ({application.coveragePercent}% match)
          </Link>
        ) : (
          <button
            onClick={runTailor}
            disabled={tailoring}
            className="rounded-md border border-slate-300 px-4 py-2 text-sm hover:bg-slate-50 disabled:opacity-50"
          >
            {tailoring ? 'Matching your CV… (1–2 min)' : 'Tailor for this job'}
          </button>
        )}
        {tailoring && (
          <p className="mt-2 text-xs text-slate-500">
            A local model is matching your CV bullets to this ad. Nothing leaves your machine.
          </p>
        )}
        {tailorError && (
          <p className="mt-2 rounded-md bg-red-50 p-3 text-sm text-red-700">{tailorError}</p>
        )}
      </div>

      <section className="mt-8">
        <h2 className="mb-2 text-sm font-semibold uppercase tracking-wide text-slate-500">
          Requirement coverage
        </h2>

        {fit ? (
          <>
            <p className="text-sm text-slate-800">
              Your own bullets answer <strong>{fit.coveragePercent}%</strong> of the{' '}
              {fit.requirementCount} requirement{fit.requirementCount === 1 ? '' : 's'} this ad
              states.
            </p>
            {fit.gaps.length > 0 && (
              <div className="mt-3">
                <p className="text-sm text-slate-600">Nothing in your CV answers these:</p>
                <ul className="mt-2 space-y-1">
                  {fit.gaps.map((gap) => (
                    <li
                      key={gap}
                      className="rounded-md bg-amber-50 px-3 py-2 text-sm text-amber-900"
                    >
                      {gap}
                    </li>
                  ))}
                </ul>
                <p className="mt-2 text-xs text-slate-500">
                  Quoted from the ad. This gap is the honest distance between you and the job.
                </p>
              </div>
            )}
          </>
        ) : (
          <button
            onClick={runScore}
            disabled={scoring}
            className="rounded-md border border-slate-300 px-4 py-2 text-sm hover:bg-slate-50 disabled:opacity-50"
          >
            {scoring ? 'Reading the ad… (1–2 min)' : 'Score this job'}
          </button>
        )}

        {fitError && (
          <p className="mt-2 rounded-md bg-red-50 p-3 text-sm text-red-700">{fitError}</p>
        )}
      </section>

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
