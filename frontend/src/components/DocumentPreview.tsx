import { useState } from 'react'
import { previewUrl } from '../api/archiveClient'

interface Props {
  applicationId: number
  /** Bumped by the parent on every successful save, so the preview is never left stale. */
  revision: number
}

/**
 * The document itself, in an iframe. React deliberately does not re-implement the layout —
 * this is the same HTML the renderer prints, so what you approve is what gets archived.
 */
export function DocumentPreview({ applicationId, revision }: Props) {
  const [which, setWhich] = useState<'cv' | 'letter'>('cv')

  const tab = (value: 'cv' | 'letter', label: string) => (
    <button
      onClick={() => setWhich(value)}
      className={`rounded-md px-3 py-1.5 text-sm transition ${
        which === value
          ? 'bg-slate-900 text-white'
          : 'border border-slate-300 hover:bg-slate-50'
      }`}
    >
      {label}
    </button>
  )

  return (
    <section className="mb-8">
      <h2 className="mb-2 text-sm font-semibold uppercase tracking-wide text-slate-500">
        Preview
      </h2>
      <p className="mb-3 text-xs text-slate-500">
        This is the document itself, not a summary of it — the same page the PDF is printed from.
      </p>
      <div className="mb-3 flex gap-2">
        {tab('cv', 'CV')}
        {tab('letter', 'Cover letter')}
      </div>
      <iframe
        key={`${which}-${revision}`}
        title={which === 'cv' ? 'CV preview' : 'Cover letter preview'}
        src={previewUrl(applicationId, which, revision)}
        className="h-[600px] w-full rounded-md border border-slate-300 bg-white"
      />
    </section>
  )
}
