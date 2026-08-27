package se.caiowain.jobseeker.api.dto;

import se.caiowain.jobseeker.domain.AtsVendor;

import java.time.Instant;

public record JobSummaryDto(
        Long id, String title, String employerName, String municipality,
        AtsVendor atsVendor, String applyUrl, Instant publishedAt, Instant lastSeenAt) {
}
