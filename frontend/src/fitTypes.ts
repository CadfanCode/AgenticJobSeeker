export type LanguageLevel =
  | 'NONE'
  | 'BASIC'
  | 'CONVERSATIONAL'
  | 'PROFESSIONAL'
  | 'FLUENT'
  | 'NATIVE'

export type RemotePolicy = 'ONSITE_ONLY' | 'HYBRID_OK' | 'REMOTE_ONLY'

/** UNKNOWN is not a pass — the gate could not read a requirement, so nothing is claimed. */
export type GateVerdict = 'PASS' | 'FLAG' | 'FAIL' | 'UNKNOWN'

export type TriageState = 'NEW' | 'SHORTLISTED' | 'DISMISSED'

export const LANGUAGE_LEVELS: LanguageLevel[] = [
  'NONE',
  'BASIC',
  'CONVERSATIONAL',
  'PROFESSIONAL',
  'FLUENT',
  'NATIVE',
]

export interface CandidateLanguage {
  language: string
  level: LanguageLevel
}

export interface Preferences {
  homeMunicipality: string | null
  acceptableMunicipalities: string | null
  remotePolicy: RemotePolicy
  dealBreakers: string | null
  languages: CandidateLanguage[]
}

export interface PrescreenSummary {
  postings: number
  withMatches: number
  computedAt: string
}

export interface DeepFit {
  jobId: number
  coveragePercent: number
  requirementCount: number
  /** Requirements nothing in your CV answers. Quoted from the ad, never a claim about you. */
  gaps: string[]
  modelUsed: string | null
  deepScoredAt: string
}
