export type SourceId = 'JOBTECH' | 'TEAMTAILOR' | 'VARBI'

export interface JobSummary {
  id: number
  title: string
  employerName: string | null
  municipality: string | null
  atsVendor: string
  applyUrl: string | null
  publishedAt: string | null
  lastSeenAt: string | null
}

export interface JobSource {
  source: SourceId
  sourceAdId: string
  sourceUrl: string | null
  fetchedAt: string
}

export interface JobDetail extends JobSummary {
  employerOrgNumber: string | null
  description: string | null
  language: string | null
  canonicalUrl: string
  deadlineAt: string | null
  firstSeenAt: string
  sources: JobSource[]
}

export interface Page<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface IngestRun {
  id: number
  source: SourceId
  startedAt: string
  finishedAt: string | null
  fetched: number
  created: number
  merged: number
  errors: number
  status: 'RUNNING' | 'COMPLETED' | 'FAILED'
  message: string | null
}

export interface Stats {
  totalJobs: number
  jobsBySource: Record<string, number>
  jobsByVendor: Record<string, number>
  activeTenants: number
  lastRun: IngestRun | null
}

export interface JobFilters {
  q?: string
  municipality?: string
  vendor?: string
  source?: string
  page?: number
  size?: number
}
