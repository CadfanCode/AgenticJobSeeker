package se.caiowain.jobseeker.tailor.api.dto;

import java.time.Instant;

public record ApplicationSummaryDto(Long id, Long jobId, String jobTitle, String employerName,
                                    String status, int coveragePercent, Instant generatedAt) {
}
