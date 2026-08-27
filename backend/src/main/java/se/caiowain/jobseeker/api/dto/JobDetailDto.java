package se.caiowain.jobseeker.api.dto;

import se.caiowain.jobseeker.domain.AtsVendor;

import java.time.Instant;
import java.util.List;

public record JobDetailDto(
        Long id, String title, String employerName, String employerOrgNumber,
        String municipality, String description, String language, AtsVendor atsVendor,
        String applyUrl, String canonicalUrl, Instant publishedAt, Instant deadlineAt,
        Instant firstSeenAt, Instant lastSeenAt, List<JobSourceDto> sources) {
}
