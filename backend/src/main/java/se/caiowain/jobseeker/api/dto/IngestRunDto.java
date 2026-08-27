package se.caiowain.jobseeker.api.dto;

import se.caiowain.jobseeker.domain.RunStatus;
import se.caiowain.jobseeker.domain.SourceId;

import java.time.Instant;

public record IngestRunDto(Long id, SourceId source, Instant startedAt, Instant finishedAt,
                           int fetched, int created, int merged, int errors,
                           RunStatus status, String message) {
}
