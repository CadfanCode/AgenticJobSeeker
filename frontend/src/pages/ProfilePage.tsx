import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import {
  approveProfile, fetchProfile, fetchSourceText, reextractProfile, saveProfile,
} from '../api/profileClient'
import { ProfileReview } from '../components/ProfileReview'
import { ProfileUpload } from '../components/ProfileUpload'
import type { Profile } from '../profileTypes'

export function ProfilePage() {
  const [profile, setProfile] = useState<Profile | null>(null)
  const [sourceText, setSourceText] = useState('')
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const loadSource = () => { fetchSourceText().then(setSourceText).catch(() => setSourceText('')) }

  useEffect(() => {
    fetchProfile()
      .then((p) => { setProfile(p); if (p) loadSource() })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false))
  }, [])

  const run = async (action: () => Promise<Profile>, note: string) => {
    setBusy(true); setError(null); setMessage(null)
    try {
      setProfile(await action())
      setMessage(note)
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setBusy(false)
    }
  }

  const unverified =
    (profile?.experiences.filter((e) => !e.verified).length ?? 0) +
    (profile?.education.filter((e) => !e.verified).length ?? 0)

  return (
    <div className="mx-auto max-w-6xl px-6 py-8">
      <nav className="mb-4 flex gap-4 text-sm">
        <Link to="/" className="text-slate-600 hover:underline">Jobs</Link>
        <span className="font-medium text-slate-900">My CV profile</span>
      </nav>

      {loading && <p className="text-sm text-slate-500">Loading…</p>}

      {!loading && !profile && (
        <ProfileUpload onUploaded={(p) => { setProfile(p); loadSource() }} />
      )}

      {profile && (
        <>
          <header className="mb-6 flex flex-wrap items-start justify-between gap-4 border-b border-slate-200 pb-4">
            <div>
              <h1 className="text-2xl font-semibold tracking-tight text-slate-900">
                {profile.fullName ?? 'Your CV profile'}
              </h1>
              <p className="mt-1 text-sm text-slate-600">
                {profile.status === 'READY' ? 'Approved' : 'Needs review'}
                {profile.sourceFilename ? ` · from ${profile.sourceFilename}` : ''}
                {profile.modelUsed ? ` · extracted by ${profile.modelUsed}` : ''}
              </p>
              {unverified > 0 && (
                <p className="mt-1 text-sm font-medium text-amber-700">
                  {unverified} entr{unverified === 1 ? 'y' : 'ies'} could not be found in your PDF —
                  check the highlighted fields.
                </p>
              )}
            </div>
            <div className="flex gap-2">
              <button
                onClick={() => run(() => saveProfile(profile), 'Saved.')}
                disabled={busy}
                className="rounded-md border border-slate-300 px-3 py-2 text-sm hover:bg-slate-50 disabled:opacity-50"
              >
                Save
              </button>
              <button
                onClick={() => run(async () => { const p = await reextractProfile(); loadSource(); return p }, 'Re-extracted.')}
                disabled={busy}
                className="rounded-md border border-slate-300 px-3 py-2 text-sm hover:bg-slate-50 disabled:opacity-50"
              >
                Re-extract
              </button>
              <button
                onClick={() => run(() => approveProfile(), 'Profile approved.')}
                disabled={busy}
                className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-700 disabled:opacity-50"
              >
                Approve profile
              </button>
            </div>
          </header>

          {message && <p className="mb-4 rounded-md bg-emerald-50 p-3 text-sm text-emerald-800">{message}</p>}
          {error && <p className="mb-4 rounded-md bg-red-50 p-3 text-sm text-red-700">{error}</p>}

          {profile.status === 'EXTRACTION_FAILED' ? (
            <p className="rounded-md bg-amber-50 p-4 text-sm text-amber-800">
              Extraction did not complete. Your PDF and its text are stored — press
              <strong> Re-extract </strong> to try again.
            </p>
          ) : (
            <ProfileReview profile={profile} sourceText={sourceText} onChange={setProfile} />
          )}
        </>
      )}
    </div>
  )
}
