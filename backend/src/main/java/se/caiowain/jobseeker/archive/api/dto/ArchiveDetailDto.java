package se.caiowain.jobseeker.archive.api.dto;

import java.time.Instant;

/**
 * @param jobApplyUrl shown so the candidate can find the posting again. Nothing dereferences it.
 * @param cvPdfSha256 lets the candidate assert which file was sent, not merely describe it.
 */
public record ArchiveDetailDto(Long id, String jobTitle, String employerName,
                               String jobCanonicalUrl, String jobApplyUrl,
                               String jobDescriptionText, String letterText,
                               int coveragePercent, String renderedBy, Instant approvedAt,
                               String cvPdfSha256, String letterPdfSha256) {
}
