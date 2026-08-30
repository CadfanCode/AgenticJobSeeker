import type { DeepFit, Preferences, PrescreenSummary, TriageState } from '../fitTypes'

async function readError(response: Response): Promise<string> {
  try {
    const body = await response.json()
    const violations = Array.isArray(body.violations) ? ` (${body.violations.join('; ')})` : ''
    return (body.detail ?? body.message ?? `${response.status} ${response.statusText}`) + violations
  } catch {
    return `${response.status} ${response.statusText}`
  }
}

async function json<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, init)
  if (!response.ok) throw new Error(await readError(response))
  return response.json() as Promise<T>
}

export function fetchPreferences(): Promise<Preferences> {
  return json<Preferences>('/api/preferences')
}

export function savePreferences(preferences: Preferences): Promise<Preferences> {
  return json<Preferences>('/api/preferences', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(preferences),
  })
}

/** Deterministic and corpus-wide. Runs in well under a second, with Ollama stopped. */
export function runPrescreen(): Promise<PrescreenSummary> {
  return json<PrescreenSummary>('/api/fit/prescreen', { method: 'POST' })
}

/** Runs a local model over one ad. One to two minutes on this hardware. */
export function scoreJob(jobId: number): Promise<DeepFit> {
  return json<DeepFit>(`/api/jobs/${jobId}/fit`, { method: 'POST' })
}

/** Resolves to null when the job has not been deep-scored — a 404 here is expected. */
export async function fetchDeepFit(jobId: number): Promise<DeepFit | null> {
  const response = await fetch(`/api/jobs/${jobId}/fit`)
  if (response.status === 404) return null
  if (!response.ok) throw new Error(await readError(response))
  return response.json() as Promise<DeepFit>
}

export function setTriage(jobId: number, state: TriageState, note?: string): Promise<unknown> {
  return json(`/api/jobs/${jobId}/triage`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ state, note: note ?? null }),
  })
}
