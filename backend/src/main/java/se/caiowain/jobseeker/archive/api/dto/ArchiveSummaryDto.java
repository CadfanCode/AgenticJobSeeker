package se.caiowain.jobseeker.archive.api.dto;

import java.time.Instant;

public record ArchiveSummaryDto(Long id, String jobTitle, String employerName,
                                int coveragePercent, Instant approvedAt) {
}
