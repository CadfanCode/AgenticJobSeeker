import type { Application, ApplicationSummary } from '../applicationTypes'
import type { Page } from '../types'

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

/** Generation runs a local model and takes one to two minutes. */
export function tailorJob(jobId: number): Promise<Application> {
  return json<Application>(`/api/jobs/${jobId}/tailor`, { method: 'POST' })
}

/** Resolves to null when the job has no application yet — a 404 here is expected. */
export async function fetchApplicationForJob(jobId: number): Promise<Application | null> {
  const response = await fetch(`/api/jobs/${jobId}/application`)
  if (response.status === 404) return null
  if (!response.ok) throw new Error(await readError(response))
  return response.json() as Promise<Application>
}

export function fetchApplications(): Promise<Page<ApplicationSummary>> {
  return json<Page<ApplicationSummary>>('/api/applications')
}

export function fetchApplication(id: number): Promise<Application> {
  return json<Application>(`/api/applications/${id}`)
}

export function saveLetter(id: number, prose: string): Promise<Application> {
  return json<Application>(`/api/applications/${id}/letter`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ prose }),
  })
}

export function approveApplication(id: number): Promise<Application> {
  return json<Application>(`/api/applications/${id}/approve`, { method: 'POST' })
}

export function discardApplication(id: number): Promise<Application> {
  return json<Application>(`/api/applications/${id}`, { method: 'DELETE' })
}
