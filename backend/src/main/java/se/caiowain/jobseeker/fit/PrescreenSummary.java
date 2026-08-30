package se.caiowain.jobseeker.fit;

import java.time.Instant;

public record PrescreenSummary(int postings, int withMatches, Instant computedAt) {
}
