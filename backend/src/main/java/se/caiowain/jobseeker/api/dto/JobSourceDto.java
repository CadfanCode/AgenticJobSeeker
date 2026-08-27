package se.caiowain.jobseeker.api.dto;

import se.caiowain.jobseeker.domain.SourceId;

import java.time.Instant;

public record JobSourceDto(SourceId source, String sourceAdId, String sourceUrl, Instant fetchedAt) {
}
