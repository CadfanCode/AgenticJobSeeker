import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { fetchPreferences, savePreferences } from '../api/fitClient'
import type { CandidateLanguage, LanguageLevel, Preferences, RemotePolicy } from '../fitTypes'
import { LANGUAGE_LEVELS } from '../fitTypes'

const EMPTY: Preferences = {
  homeMunicipality: '',
  acceptableMunicipalities: '',
  remotePolicy: 'HYBRID_OK',
  dealBreakers: '',
  languages: [],
}

export function PreferencesPage() {
  const [prefs, setPrefs] = useState<Preferences>(EMPTY)
  const [status, setStatus] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    fetchPreferences()
      .then((loaded) => setPrefs({ ...EMPTY, ...loaded }))
      .catch((e: Error) => setError(e.message))
  }, [])

  const set = (patch: Partial<Preferences>) => setPrefs({ ...prefs, ...patch })

  const setLanguage = (index: number, patch: Partial<CandidateLanguage>) => {
    const languages = prefs.languages.map((l, i) => (i === index ? { ...l, ...patch } : l))
    set({ languages })
  }

  const save = async () => {
    setStatus(null)
    setError(null)
    try {
      const saved = await savePreferences(prefs)
      setPrefs({ ...EMPTY, ...saved })
      setStatus('Saved. Re-run the ranking on the jobs page to apply it.')
    } catch (e) {
      setError((e as Error).message)
    }
  }

  return (
    <div className="mx-auto max-w-2xl px-6 py-8">
      <nav className="mb-4 flex gap-4 text-sm">
        <Link to="/" className="text-slate-600 hover:underline">Jobs</Link>
        <Link to="/profile" className="text-slate-600 hover:underline">My CV profile</Link>
        <span className="font-medium text-slate-900">Preferences</span>
      </nav>

      <h1 className="text-2xl font-semibold tracking-tight text-slate-900">Preferences</h1>
      <p className="mt-1 text-sm text-slate-600">
        What you will accept. These are your decisions, kept separately from your CV, so
        re-uploading a CV never overwrites them.
      </p>

      <section className="mt-8">
        <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-500">Languages</h2>
        <p className="mt-1 text-xs text-slate-500">
          An ad demanding a language that is not listed here is vetoed outright. One demanding
          a level above yours is flagged, not vetoed — bars like “flytande” vary by employer.
        </p>

        <ul className="mt-3 space-y-2">
          {prefs.languages.map((language, index) => (
            <li key={index} className="flex gap-2">
              <input
                type="text"
                value={language.language}
                placeholder="sv"
                onChange={(e) => setLanguage(index, { language: e.target.value })}
                className="w-24 rounded-md border border-slate-300 px-3 py-2 text-sm outline-none focus:border-slate-900"
              />
              <select
                value={language.level}
                onChange={(e) => setLanguage(index, { level: e.target.value as LanguageLevel })}
                className="flex-1 rounded-md border border-slate-300 px-3 py-2 text-sm outline-none focus:border-slate-900"
              >
                {LANGUAGE_LEVELS.map((level) => (
                  <option key={level} value={level}>{level}</option>
                ))}
              </select>
              <button
                onClick={() =>
                  set({ languages: prefs.languages.filter((_, i) => i !== index) })
                }
                className="rounded-md border border-slate-300 px-3 py-2 text-sm hover:bg-slate-50"
              >
                Remove
              </button>
            </li>
          ))}
        </ul>

        <button
          onClick={() =>
            set({ languages: [...prefs.languages, { language: '', level: 'CONVERSATIONAL' }] })
          }
          className="mt-3 rounded-md border border-slate-300 px-3 py-1.5 text-sm hover:bg-slate-50"
        >
          Add a language
        </button>
      </section>

      <section className="mt-8 space-y-4">
        <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-500">Location</h2>

        <label className="block">
          <span className="text-sm text-slate-700">Acceptable municipalities</span>
          <input
            type="text"
            value={prefs.acceptableMunicipalities ?? ''}
            placeholder="Stockholm, Solna, Sundbyberg"
            onChange={(e) => set({ acceptableMunicipalities: e.target.value })}
            className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm outline-none focus:border-slate-900"
          />
          <span className="mt-1 block text-xs text-slate-500">
            Comma-separated. Leave empty to check nothing.
          </span>
        </label>

        <label className="block">
          <span className="text-sm text-slate-700">Remote policy</span>
          <select
            value={prefs.remotePolicy}
            onChange={(e) => set({ remotePolicy: e.target.value as RemotePolicy })}
            className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm outline-none focus:border-slate-900"
          >
            <option value="ONSITE_ONLY">On site only</option>
            <option value="HYBRID_OK">Hybrid is fine</option>
            <option value="REMOTE_ONLY">Remote only — location is irrelevant</option>
          </select>
        </label>

        <label className="block">
          <span className="text-sm text-slate-700">Deal-breakers</span>
          <textarea
            value={prefs.dealBreakers ?? ''}
            rows={3}
            placeholder="Notes for yourself; not evaluated automatically."
            onChange={(e) => set({ dealBreakers: e.target.value })}
            className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm outline-none focus:border-slate-900"
          />
        </label>
      </section>

      <div className="mt-8 flex items-center gap-3">
        <button
          onClick={save}
          className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white transition hover:bg-slate-700"
        >
          Save preferences
        </button>
        {status && <span className="text-sm text-slate-600">{status}</span>}
      </div>

      {error && (
        <p className="mt-4 rounded-md bg-red-50 p-3 text-sm text-red-700">{error}</p>
      )}
    </div>
  )
}
