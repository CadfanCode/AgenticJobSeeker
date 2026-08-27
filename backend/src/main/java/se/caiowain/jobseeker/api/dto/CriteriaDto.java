package se.caiowain.jobseeker.api.dto;

public record CriteriaDto(Long id, String name, String query, String municipalityCodes,
                          String municipalityNames, String occupationFieldCodes, boolean enabled) {
}
