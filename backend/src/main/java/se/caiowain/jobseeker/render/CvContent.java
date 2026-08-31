package se.caiowain.jobseeker.render;

import java.util.List;

/**
 * A CV as a reader sees it. Named {@code CvContent} rather than {@code CvDocument} because
 * {@code profile.domain.CvDocument} is already the uploaded PDF from Slice 2a.
 */
public record CvContent(String fullName, String headline, String email, String phone,
                        String location, String summary, List<String> skills,
                        List<Experience> experiences) {

    public record Experience(String employer, String title, String startDate, String endDate,
                             String location, List<String> bullets) {
    }
}
