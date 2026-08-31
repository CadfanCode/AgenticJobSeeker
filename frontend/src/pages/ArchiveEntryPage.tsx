import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { cvPdfUrl, fetchArchiveEntry, letterPdfUrl } from '../api/archiveClient'
import type { ArchiveDetail } from '../archiveTypes'
import { CoverageBar } from '../components/CoverageBar'

export function ArchiveEntryPage() {
  const { id } = useParams<{ id: string }>()
  const [entry, setEntry] = useState<ArchiveDetail | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)
  const [copyError, setCopyError] = useState<string | null>(null)

  useEffect(() => {
    if (!id) return
    fetchArchiveEntry(Number(id)).then(setEntry).catch((e: Error) => setError(e.message))
  }, [id])

  const copyLetter = async () => {
    setCopyError(null)
    try {
      await navigator.clipboard.writeText(entry?.letterText ?? '')
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      setCopyError('Could not copy automatically — select the text below and copy it by hand.')
    }
  }

  if (error) {
    return <p className="mx-auto max-w-4xl px-6 py-8 text-sm text-red-700">{error}</p>
  }
  if (!entry) {
    return <p className="mx-auto max-w-4xl px-6 py-8 text-sm text-slate-500">Loading…</p>
  }

  return (
    <div className="mx-auto max-w-4xl px-6 py-8">
      <nav className="mb-4 flex gap-4 text-sm">
        <Link to="/" className="text-slate-600 hover:underline">Jobs</Link>
        <Link to="/applications" className="text-slate-600 hover:underline">Applications</Link>
        <Link to="/archive" className="text-slate-600 hover:underline">Archive</Link>
      </nav>

      <header className="mb-6 border-b border-slate-200 pb-4">
        <h1 className="text-2xl font-semibold tracking-tight text-slate-900">{entry.jobTitle}</h1>
        <p className="mt-1 text-sm text-slate-600">
          {entry.employerName ?? 'Unknown employer'} · approved{' '}
          {new Date(entry.approvedAt).toLocaleString()}
        </p>
        <div className="mt-3">
          <CoverageBar percent={entry.coveragePercent} />
        </div>
      </header>

      <div className="mb-8 flex flex-wrap gap-2">
        <a
          href={cvPdfUrl(entry.id)}
          target="_blank"
          rel="noreferrer"
          className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white transition hover:bg-slate-700"
        >
          Open the CV
        </a>
        <a
          href={letterPdfUrl(entry.id)}
          target="_blank"
          rel="noreferrer"
          className="rounded-md border border-slate-300 px-4 py-2 text-sm hover:bg-slate-50"
        >
          Open the cover letter
        </a>
      </div>

      <section className="mb-8">
        <h2 className="mb-2 text-sm font-semibold uppercase tracking-wide text-slate-500">
          The ad you answered
        </h2>
        <p className="mb-2 text-xs text-slate-500">
          Copied when you approved this application. The live posting may since have been edited
          or taken down — this is the version you actually answered.
        </p>
        <p className="whitespace-pre-wrap rounded-md bg-slate-50 p-4 text-sm leading-relaxed text-slate-800">
          {entry.jobDescriptionText ?? 'No description was captured.'}
        </p>
        {entry.jobApplyUrl && (
          <p className="mt-2 break-all text-xs text-slate-500">
            Applied via: {entry.jobApplyUrl}
          </p>
        )}
      </section>

      <section className="mb-8">
        <h2 className="mb-2 text-sm font-semibold uppercase tracking-wide text-slate-500">
          Your cover letter, as text
        </h2>
        <p className="mb-2 text-xs text-slate-500">
          For ATS forms that want the letter pasted into a box rather than uploaded as a file.
        </p>
        <div className="mb-2 flex items-center gap-2">
          <button
            onClick={copyLetter}
            className="rounded-md border border-slate-300 px-3 py-1.5 text-sm hover:bg-slate-50"
          >
            {copied ? 'Copied' : 'Copy to clipboard'}
          </button>
          {copyError && <span className="text-xs text-red-700">{copyError}</span>}
        </div>
        <p className="whitespace-pre-wrap rounded-md bg-slate-50 p-4 text-sm leading-relaxed text-slate-800">
          {entry.letterText ?? 'No letter text was captured.'}
        </p>
      </section>

      <section>
        <h2 className="mb-2 text-sm font-semibold uppercase tracking-wide text-slate-500">
          File fingerprints
        </h2>
        <p className="mb-2 text-xs text-slate-500">
          SHA-256 of the exact files stored — proof of which bytes were sent, not just what they
          say.
        </p>
        <dl className="space-y-1 font-mono text-xs text-slate-600">
          <div>
            <dt className="inline text-slate-500">CV </dt>
            <dd className="inline break-all">{entry.cvPdfSha256}</dd>
          </div>
          <div>
            <dt className="inline text-slate-500">Letter </dt>
            <dd className="inline break-all">{entry.letterPdfSha256}</dd>
          </div>
        </dl>
        {entry.renderedBy && (
          <p className="mt-2 text-xs text-slate-500">Rendered by {entry.renderedBy}</p>
        )}
      </section>
    </div>
  )
}
