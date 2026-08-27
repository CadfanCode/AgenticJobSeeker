package se.caiowain.jobseeker.profile.extract;

import java.util.List;

/**
 * The shape the LLM is asked to produce. Kept separate from the JPA entities so the
 * model's output can be validated before anything is persisted.
 */
public record ExtractedProfile(
        String fullName,
        String headline,
        String email,
        String phone,
        String location,
        String summary,
        String language,
        List<ExtractedExperience> experiences,
        List<ExtractedEducation> education,
        List<ExtractedSkill> skills
) {

    public record ExtractedExperience(
            String employer,
            String title,
            String startDate,
            String endDate,
            boolean current,
            String location,
            List<String> bullets
    ) {
    }

    public record ExtractedEducation(
            String institution,
            String degree,
            String fieldOfStudy,
            String startDate,
            String endDate
    ) {
    }

    public record ExtractedSkill(String name, String category) {
    }
}
