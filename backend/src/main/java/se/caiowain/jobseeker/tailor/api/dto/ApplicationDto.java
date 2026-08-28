package se.caiowain.jobseeker.tailor.api.dto;

import java.time.Instant;
import java.util.List;

public record ApplicationDto(Long id, Long jobId, String jobTitle, String employerName,
                             String jobApplyUrl, String status, String modelUsed,
                             int coveragePercent, Instant generatedAt, Instant reviewedAt,
                             String letterProse, List<RequirementDto> requirements) {
}
