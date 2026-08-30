package se.caiowain.jobseeker.fit.api.dto;

import se.caiowain.jobseeker.fit.domain.TriageState;

public record TriageRequest(TriageState state, String note) {
}
