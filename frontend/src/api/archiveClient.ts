import type { ArchiveDetail, ArchiveSummary } from '../archiveTypes'
import type { Page } from '../types'

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

export function fetchArchive(page = 0): Promise<Page<ArchiveSummary>> {
  return json<Page<ArchiveSummary>>(`/api/archive?page=${page}`)
}

export function fetchArchiveEntry(id: number): Promise<ArchiveDetail> {
  return json<ArchiveDetail>(`/api/archive/${id}`)
}

/** The frozen files. Served inline so the browser's own viewer opens them. */
export function cvPdfUrl(id: number): string {
  return `/api/archive/${id}/cv.pdf`
}

export function letterPdfUrl(id: number): string {
  return `/api/archive/${id}/letter.pdf`
}

/**
 * The preview is the document itself, served as HTML for an iframe.
 *
 * `revision` is a cache-busting query parameter, not a server-side concept — the backend
 * ignores it. Without it the iframe's `src` is identical before and after a save, so the
 * browser is free to reuse its cached response and never refetch the edited document.
 */
export function previewUrl(applicationId: number, which: 'cv' | 'letter', revision: number): string {
  return `/api/applications/${applicationId}/preview/${which}?r=${revision}`
}
