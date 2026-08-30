package se.caiowain.jobseeker.fit.api.dto;

import se.caiowain.jobseeker.fit.domain.LanguageLevel;

public record CandidateLanguageDto(String language, LanguageLevel level) {
}
