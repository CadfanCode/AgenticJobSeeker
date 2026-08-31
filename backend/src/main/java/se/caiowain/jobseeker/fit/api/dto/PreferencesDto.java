package se.caiowain.jobseeker.fit.api.dto;

import se.caiowain.jobseeker.fit.domain.RemotePolicy;

import java.util.List;

public record PreferencesDto(String homeMunicipality,
                             String acceptableMunicipalities,
                             RemotePolicy remotePolicy,
                             String dealBreakers,
                             List<CandidateLanguageDto> languages) {
}
