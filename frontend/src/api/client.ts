import type { IngestRun, JobDetail, JobFilters, JobSummary, Page, Stats } from '../types'

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
  if (!response.ok) {
    throw new Error(`${response.status} ${response.statusText}`)
  }
  return response.json() as Promise<T>
}

export function fetchJobs(filters: JobFilters = {}): Promise<Page<JobSummary>> {
  const params = new URLSearchParams()
  Object.entries(filters).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      params.set(key, String(value))
    }
  })
  return request<Page<JobSummary>>(`/api/jobs?${params.toString()}`)
}

export function fetchJob(id: number): Promise<JobDetail> {
  return request<JobDetail>(`/api/jobs/${id}`)
}

export function fetchStats(): Promise<Stats> {
  return request<Stats>('/api/stats')
}

export function fetchRuns(): Promise<Page<IngestRun>> {
  return request<Page<IngestRun>>('/api/ingest/runs')
}

export function triggerIngest(): Promise<IngestRun[]> {
  return request<IngestRun[]>('/api/ingest/run', { method: 'POST' })
}
