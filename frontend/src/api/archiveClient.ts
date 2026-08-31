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

export function fetchArchive(): Promise<Page<ArchiveSummary>> {
  return json<Page<ArchiveSummary>>('/api/archive')
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

/** The preview is the document itself, served as HTML for an iframe. */
export function previewUrl(applicationId: number, which: 'cv' | 'letter'): string {
  return `/api/applications/${applicationId}/preview/${which}`
}
