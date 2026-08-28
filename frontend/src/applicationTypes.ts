export type ApplicationStatus = 'DRAFT' | 'APPROVED' | 'DISCARDED' | 'GENERATION_FAILED'

export interface Evidence {
  id: number
  cvExperienceBulletId: number | null
  bulletText: string
  ordinal: number
}

export interface Requirement {
  id: number
  text: string
  ordinal: number
  overBroad: boolean
  evidence: Evidence[]
}

export interface Application {
  id: number
  jobId: number
  jobTitle: string
  employerName: string | null
  jobApplyUrl: string | null
  status: ApplicationStatus
  modelUsed: string | null
  coveragePercent: number
  generatedAt: string
  reviewedAt: string | null
  letterProse: string | null
  requirements: Requirement[]
}

export interface ApplicationSummary {
  id: number
  jobId: number
  jobTitle: string
  employerName: string | null
  status: ApplicationStatus
  coveragePercent: number
  generatedAt: string
}
