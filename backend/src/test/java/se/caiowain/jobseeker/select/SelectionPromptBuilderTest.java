package se.caiowain.jobseeker.select;

import org.junit.jupiter.api.Test;
import se.caiowain.jobseeker.profile.domain.CvExperience;
import se.caiowain.jobseeker.profile.domain.CvExperienceBullet;
import se.caiowain.jobseeker.profile.domain.CvProfile;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SelectionPromptBuilderTest {

    private final SelectionPromptBuilder builder = new SelectionPromptBuilder();

    private CvProfile profileWithBullets(String... texts) {
        CvProfile profile = new CvProfile();
        CvExperience experience = new CvExperience();
        experience.setEmployer("Nordic Systems AB");
        experience.setTitle("Senior Software Engineer");
        experience.setOrdinal(0);
        profile.addExperience(experience);
        int i = 0;
        for (String t : texts) {
            CvExperienceBullet bullet = new CvExperienceBullet();
            bullet.setText(t);
            bullet.setOrdinal(i++);
            experience.addBullet(bullet);
        }
        return profile;
    }

    @Test
    void numbersBulletsFromOneUpwards() {
        List<NumberedBullet> numbered = builder.numberBullets(
                profileWithBullets("Built REST APIs", "Reduced deployment time"));

        assertThat(numbered).hasSize(2);
        assertThat(numbered.get(0).index()).isEqualTo(1);
        assertThat(numbered.get(1).index()).isEqualTo(2);
        assertThat(numbered.get(0).text()).isEqualTo("Built REST APIs");
    }

    @Test
    void skipsBlankBullets() {
        assertThat(builder.numberBullets(profileWithBullets("Built REST APIs", "  ", "")))
                .hasSize(1);
    }

    @Test
    void userPromptContainsTheDescriptionAndEveryNumberedBullet() {
        List<NumberedBullet> numbered = builder.numberBullets(
                profileWithBullets("Built REST APIs", "Reduced deployment time"));

        String prompt = builder.userPrompt("MARKER-JOB-TEXT", numbered);

        assertThat(prompt).contains("MARKER-JOB-TEXT");
        assertThat(prompt).contains("1. Built REST APIs");
        assertThat(prompt).contains("2. Reduced deployment time");
    }

    @Test
    void systemPromptForbidsWritingClaims() {
        String system = builder.systemPrompt();
        assertThat(system).containsIgnoringCase("verbatim");
        assertThat(system).containsIgnoringCase("never");
        assertThat(system).containsIgnoringCase("json");
    }

    @Test
    void handlesAProfileWithNoBullets() {
        assertThat(builder.numberBullets(new CvProfile())).isEmpty();
    }
}
