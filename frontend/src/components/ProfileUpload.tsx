import { useRef, useState } from 'react'
import { uploadCv } from '../api/profileClient'
import type { Profile } from '../profileTypes'

export function ProfileUpload({ onUploaded }: { onUploaded: (p: Profile) => void }) {
  const input = useRef<HTMLInputElement>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const send = async (file: File) => {
    setBusy(true)
    setError(null)
    try {
      onUploaded(await uploadCv(file))
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="rounded-lg border-2 border-dashed border-slate-300 p-10 text-center">
      <h2 className="text-lg font-medium text-slate-900">Upload your CV</h2>
      <p className="mt-1 text-sm text-slate-600">
        A PDF, up to 10 MB. It is read once and structured for you to review.
      </p>

      <input
        ref={input}
        type="file"
        accept="application/pdf"
        className="hidden"
        onChange={(e) => {
          const file = e.target.files?.[0]
          if (file) void send(file)
        }}
      />

      <button
        onClick={() => input.current?.click()}
        disabled={busy}
        className="mt-5 rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white transition hover:bg-slate-700 disabled:opacity-50"
      >
        {busy ? 'Extracting…' : 'Choose PDF'}
      </button>

      {busy && (
        <p className="mt-3 text-xs text-slate-500">
          Reading the PDF and structuring it. This takes a few seconds.
        </p>
      )}
      {error && <p className="mt-4 rounded-md bg-red-50 p-3 text-sm text-red-700">{error}</p>}
    </div>
  )
}
