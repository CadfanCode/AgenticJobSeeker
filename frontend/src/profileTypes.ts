export type ProfileStatus = 'NEEDS_REVIEW' | 'READY' | 'EXTRACTION_FAILED'

export interface Bullet {
  id?: number
  text: string
  ordinal: number
}

export interface Experience {
  id?: number
  employer: string | null
  title: string | null
  startDate: string | null
  endDate: string | null
  current: boolean
  location: string | null
  ordinal: number
  verified: boolean
  verificationNotes: string | null
  bullets: Bullet[]
}

export interface Education {
  id?: number
  institution: string | null
  degree: string | null
  fieldOfStudy: string | null
  startDate: string | null
  endDate: string | null
  ordinal: number
  verified: boolean
  verificationNotes: string | null
}

export interface Skill {
  id?: number
  name: string
  category: string | null
  ordinal: number
}

export interface Profile {
  id: number
  fullName: string | null
  headline: string | null
  email: string | null
  phone: string | null
  location: string | null
  summary: string | null
  language: string | null
  status: ProfileStatus
  modelUsed: string | null
  extractedAt: string | null
  reviewedAt: string | null
  sourceFilename: string | null
  experiences: Experience[]
  education: Education[]
  skills: Skill[]
}
