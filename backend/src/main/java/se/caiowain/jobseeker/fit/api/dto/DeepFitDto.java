package se.caiowain.jobseeker.fit.api.dto;

import java.time.Instant;
import java.util.List;

public record DeepFitDto(Long jobId, int coveragePercent, int requirementCount,
                         List<String> gaps, String modelUsed, Instant deepScoredAt) {
}
