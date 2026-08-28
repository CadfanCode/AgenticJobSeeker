package se.caiowain.jobseeker.profile.api.dto;

import java.util.List;

public record ExperienceDto(Long id, String employer, String title, String startDate,
                            String endDate, boolean current, String location, int ordinal,
                            boolean verified, String verificationNotes, List<BulletDto> bullets) {
}
