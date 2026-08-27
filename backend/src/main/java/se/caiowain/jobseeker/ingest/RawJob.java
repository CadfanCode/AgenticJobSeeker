package se.caiowain.jobseeker.ingest;

import java.time.Instant;

/**
 * Source-agnostic representation of one job as fetched. Every {@link JobSource}
 * adapter maps its own payload shape into this record.
 *
 * @param rawPayload the original payload as JSON, retained so later slices can
 *                   re-derive fields without re-crawling the source
 */
public record RawJob(
        String sourceAdId,
        String sourceUrl,
        String title,
        String employerName,
        String employerOrgNumber,
        String municipality,
        String description,
        String language,
        String applyUrl,
        Instant publishedAt,
        Instant deadlineAt,
        String rawPayload
) {
}
