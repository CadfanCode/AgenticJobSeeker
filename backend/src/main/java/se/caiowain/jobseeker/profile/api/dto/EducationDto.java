package se.caiowain.jobseeker.profile.api.dto;

public record EducationDto(Long id, String institution, String degree, String fieldOfStudy,
                           String startDate, String endDate, int ordinal,
                           boolean verified, String verificationNotes) {
}
