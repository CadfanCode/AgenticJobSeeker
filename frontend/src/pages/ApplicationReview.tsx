import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import {
  approveApplication, discardApplication, fetchApplication, saveLetter,
} from '../api/applicationClient'
import type { Application } from '../applicationTypes'
import { CoverageBar } from '../components/CoverageBar'

export function ApplicationReview() {
  const { id } = useParams<{ id: string }>()
  const [app, setApp] = useState<Application | null>(null)
  const [prose, setProse] = useState('')
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!id) return
    fetchApplication(Number(id))
      .then((a) => { setApp(a); setProse(a.letterProse ?? '') })
      .catch((e: Error) => setError(e.message))
  }, [id])

  const run = async (action: () => Promise<Application>, note: string) => {
    setBusy(true); setError(null); setMessage(null)
    try { setApp(await action()); setMessage(note) }
    catch (e) { setError((e as Error).message) }
    finally { setBusy(false) }
  }

  if (error && !app) {
    return <p className="mx-auto max-w-4xl px-6 py-8 text-sm text-red-700">{error}</p>
  }
  if (!app) {
    return <p className="mx-auto max-w-4xl px-6 py-8 text-sm text-slate-500">Loading…</p>
  }

  const unmatched = app.requirements.filter((r) => r.evidence.length === 0)

  return (
    <div className="mx-auto max-w-4xl px-6 py-8">
      <nav className="mb-4 flex gap-4 text-sm">
        <Link to="/" className="text-slate-600 hover:underline">Jobs</Link>
        <Link to="/applications" className="text-slate-600 hover:underline">Applications</Link>
        <span className="font-medium text-slate-900">{app.jobTitle}</span>
      </nav>

      <header className="mb-6 flex flex-wrap items-start justify-between gap-4 border-b border-slate-200 pb-4">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight text-slate-900">{app.jobTitle}</h1>
          <p className="mt-1 text-sm text-slate-600">
            {app.employerName ?? 'Unknown employer'} · {app.status}
            {app.modelUsed ? ` · matched locally by ${app.modelUsed}` : ''}
          </p>
          <div className="mt-3"><CoverageBar percent={app.coveragePercent} /></div>
        </div>
        <div className="flex gap-2">
          <button onClick={() => run(() => saveLetter(app.id, prose), 'Saved.')} disabled={busy}
                  className="rounded-md border border-slate-300 px-3 py-2 text-sm hover:bg-slate-50 disabled:opacity-50">
            Save
          </button>
          <button onClick={() => run(() => discardApplication(app.id), 'Discarded.')} disabled={busy}
                  className="rounded-md border border-slate-300 px-3 py-2 text-sm hover:bg-slate-50 disabled:opacity-50">
            Discard
          </button>
          <button onClick={() => run(() => approveApplication(app.id), 'Approved.')} disabled={busy}
                  className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-700 disabled:opacity-50">
            Approve
          </button>
        </div>
      </header>

      {message && <p className="mb-4 rounded-md bg-emerald-50 p-3 text-sm text-emerald-800">{message}</p>}
      {error && <p className="mb-4 rounded-md bg-red-50 p-3 text-sm text-red-700">{error}</p>}

      {unmatched.length > 0 && (
        <p className="mb-6 rounded-md bg-amber-50 p-3 text-sm text-amber-800">
          {unmatched.length} requirement{unmatched.length === 1 ? '' : 's'} had nothing in your CV to
          support {unmatched.length === 1 ? 'it' : 'them'}. That gap is the most useful thing on this page.
        </p>
      )}

      <section className="mb-8">
        <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-slate-500">
          What they ask for, and what you have
        </h2>
        <ul className="space-y-3">
          {app.requirements.map((r) => (
            <li key={r.id}
                className={`rounded-md border p-3 ${
                  r.evidence.length === 0 ? 'border-amber-400 bg-amber-50' : 'border-slate-200'
                }`}>
              <p className="text-sm font-medium text-slate-900">{r.text}</p>
              {r.overBroad && (
                <p className="mt-1 text-xs text-amber-700">
                  The model cited a lot of bullets here — check the match is real.
                </p>
              )}
              {r.evidence.length === 0 ? (
                <p className="mt-2 text-sm text-amber-800">Nothing in your CV covers this.</p>
              ) : (
                <ul className="mt-2 list-disc space-y-1 pl-5">
                  {r.evidence.map((e) => (
                    <li key={e.id} className="text-sm text-slate-700">{e.bulletText}</li>
                  ))}
                </ul>
              )}
            </li>
          ))}
        </ul>
      </section>

      <section>
        <h2 className="mb-2 text-sm font-semibold uppercase tracking-wide text-slate-500">
          Your cover letter
        </h2>
        <p className="mb-2 text-xs text-slate-500">
          The evidence above is your raw material. The prose is yours — nothing here was written for you.
        </p>
        <textarea
          value={prose}
          onChange={(e) => setProse(e.target.value)}
          rows={14}
          placeholder="Write your letter here, using the matched bullets above as evidence."
          className="w-full rounded-md border border-slate-300 px-3 py-2 font-mono text-sm outline-none focus:border-slate-900"
        />
      </section>
    </div>
  )
}
