package se.caiowain.jobseeker.api.dto;

import se.caiowain.jobseeker.domain.AtsVendor;
import se.caiowain.jobseeker.fit.domain.GateVerdict;
import se.caiowain.jobseeker.fit.domain.TriageState;

import java.time.Instant;

/**
 * The fit fields are nullable on purpose: a posting ingested since the last prescreen has
 * no row yet, and it must still be listed rather than disappearing.
 */
public record JobSummaryDto(
        Long id, String title, String employerName, String municipality,
        AtsVendor atsVendor, String applyUrl, Instant publishedAt, Instant lastSeenAt,
        Integer matchedSkillCount, String matchedSkills,
        GateVerdict languageGate, String languageNote,
        GateVerdict locationGate, Boolean deadlinePassed,
        TriageState triage) {
}
