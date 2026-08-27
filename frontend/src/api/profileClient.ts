import type { Profile } from '../profileTypes'

/** Surfaces the server's ProblemDetail message rather than a bare status code. */
async function readError(response: Response): Promise<string> {
  try {
    const body = await response.json()
    return body.detail ?? body.message ?? `${response.status} ${response.statusText}`
  } catch {
    return `${response.status} ${response.statusText}`
  }
}

async function json<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, init)
  if (!response.ok) throw new Error(await readError(response))
  return response.json() as Promise<T>
}

/** Resolves to null when no profile exists yet — a 404 here is expected, not an error. */
export async function fetchProfile(): Promise<Profile | null> {
  const response = await fetch('/api/profile')
  if (response.status === 404) return null
  if (!response.ok) throw new Error(await readError(response))
  return response.json() as Promise<Profile>
}

export async function uploadCv(file: File): Promise<Profile> {
  const form = new FormData()
  form.append('file', file)
  const response = await fetch('/api/profile/upload', { method: 'POST', body: form })
  if (!response.ok) throw new Error(await readError(response))
  return response.json() as Promise<Profile>
}

export function saveProfile(profile: Profile): Promise<Profile> {
  return json<Profile>('/api/profile', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(profile),
  })
}

export function reextractProfile(): Promise<Profile> {
  return json<Profile>('/api/profile/reextract', { method: 'POST' })
}

export function approveProfile(): Promise<Profile> {
  return json<Profile>('/api/profile/approve', { method: 'POST' })
}

export async function fetchSourceText(): Promise<string> {
  const response = await fetch('/api/profile/source-text')
  if (!response.ok) throw new Error(await readError(response))
  return response.text()
}
