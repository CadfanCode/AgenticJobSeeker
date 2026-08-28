package se.caiowain.jobseeker.profile.api.dto;

import java.time.Instant;
import java.util.List;

public record ProfileDto(Long id, String fullName, String headline, String email, String phone,
                         String location, String summary, String language, String status,
                         String modelUsed, Instant extractedAt, Instant reviewedAt,
                         String sourceFilename,
                         List<ExperienceDto> experiences, List<EducationDto> education,
                         List<SkillDto> skills) {
}
