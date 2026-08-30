package se.caiowain.jobseeker.fit.api.dto;

import jakarta.validation.constraints.NotNull;
import se.caiowain.jobseeker.fit.domain.TriageState;

public record TriageRequest(@NotNull TriageState state, String note) {
}
