import type { GateVerdict, TriageState } from './fitTypes'

export type SourceId = 'JOBTECH' | 'TEAMTAILOR' | 'VARBI'

/** Fields both `GET /api/jobs` and `GET /api/jobs/{id}` return. */
export interface JobBase {
  id: number
  title: string
  employerName: string | null
  municipality: string | null
  atsVendor: string
  applyUrl: string | null
  publishedAt: string | null
  lastSeenAt: string | null
}

/**
 * The list endpoint's shape. The fit fields are nullable because a posting ingested since
 * the last prescreen has no fit row yet and must still be listed.
 */
export interface JobSummary extends JobBase {
  matchedSkillCount: number | null
  matchedSkills: string | null
  languageGate: GateVerdict | null
  languageNote: string | null
  locationGate: GateVerdict | null
  deadlinePassed: boolean | null
  triage: TriageState | null
}

export interface JobSource {
  source: SourceId
  sourceAdId: string
  sourceUrl: string | null
  fetchedAt: string
}

/** The detail endpoint's shape. It never returns the fit fields — see `JobSummary`. */
export interface JobDetail extends JobBase {
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
  sort?: string
  triage?: string
  includeGateFailures?: boolean
  page?: number
  size?: number
}
