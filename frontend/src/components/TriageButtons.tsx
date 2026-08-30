import { useState } from 'react'
import { setTriage } from '../api/fitClient'
import type { TriageState } from '../fitTypes'

interface Props {
  jobId: number
  state: TriageState | null
  onChanged: () => void
}

export function TriageButtons({ jobId, state, onChanged }: Props) {
  const [busy, setBusy] = useState(false)

  const decide = async (next: TriageState) => {
    setBusy(true)
    try {
      await setTriage(jobId, next)
      onChanged()
    } finally {
      setBusy(false)
    }
  }

  const style = (active: boolean) =>
    `rounded-md border px-2 py-1 text-xs transition disabled:opacity-50 ${
      active ? 'border-slate-900 bg-slate-900 text-white' : 'border-slate-300 hover:bg-slate-50'
    }`

  return (
    <div className="flex gap-2">
      <button
        disabled={busy}
        onClick={() => decide(state === 'SHORTLISTED' ? 'NEW' : 'SHORTLISTED')}
        className={style(state === 'SHORTLISTED')}
      >
        Shortlist
      </button>
      <button
        disabled={busy}
        onClick={() => decide(state === 'DISMISSED' ? 'NEW' : 'DISMISSED')}
        className={style(state === 'DISMISSED')}
      >
        Dismiss
      </button>
    </div>
  )
}
