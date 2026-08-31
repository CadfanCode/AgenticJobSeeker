export interface ArchiveSummary {
  id: number
  jobTitle: string
  employerName: string | null
  coveragePercent: number
  approvedAt: string
}

export interface ArchiveDetail {
  id: number
  jobTitle: string
  employerName: string | null
  jobCanonicalUrl: string | null
  /** Shown so you can find the posting again. Nothing in the app follows it. */
  jobApplyUrl: string | null
  /** The ad as it read on the day you applied, not as it reads now. */
  jobDescriptionText: string | null
  letterText: string | null
  coveragePercent: number
  renderedBy: string | null
  approvedAt: string
  cvPdfSha256: string
  letterPdfSha256: string
}
