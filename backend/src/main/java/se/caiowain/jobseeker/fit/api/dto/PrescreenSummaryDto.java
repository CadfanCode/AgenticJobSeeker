package se.caiowain.jobseeker.fit.api.dto;

import java.time.Instant;

public record PrescreenSummaryDto(int postings, int withMatches, Instant computedAt) {
}
