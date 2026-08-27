package se.caiowain.jobseeker.profile.extract;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractionValidatorTest {

    private final ExtractionValidator validator = new ExtractionValidator();

    private static final String SOURCE = """
            Cai Wain
            Senior Engineer, Acme AB
            2019 - present
            Built the ingestion pipeline that reduced
            duplicate postings by 40%
            KTH Royal Institute of Technology, MSc Computer Science, 2014 - 2019
            """;

    private ExtractedProfile profileWith(List<ExtractedProfile.ExtractedExperience> experiences,
                                         List<ExtractedProfile.ExtractedEducation> education) {
        return new ExtractedProfile("Cai Wain", "Senior Engineer", null, null, null,
                null, "en", experiences, education, List.of());
    }

    @Test
    void verifiesAnExperienceWhoseFieldsAllAppearInTheSource() {
        var report = validator.verify(profileWith(List.of(
                new ExtractedProfile.ExtractedExperience(
                        "Acme AB", "Senior Engineer", "2019", "present", true, null, List.of())),
                List.of()), SOURCE);

        assertThat(report.notesForExperience(0)).isEmpty();
    }

    @Test
    void flagsAnInventedEmployer() {
        var report = validator.verify(profileWith(List.of(
                new ExtractedProfile.ExtractedExperience(
                        "Globex Corporation", "Senior Engineer", "2019", "present", true, null, List.of())),
                List.of()), SOURCE);

        assertThat(report.notesForExperience(0)).isPresent();
        assertThat(report.notesForExperience(0).orElseThrow()).contains("employer");
        assertThat(report.notesForExperience(0).orElseThrow()).contains("Globex Corporation");
    }

    @Test
    void flagsAShiftedDate() {
        var report = validator.verify(profileWith(List.of(
                new ExtractedProfile.ExtractedExperience(
                        "Acme AB", "Senior Engineer", "2017", "present", true, null, List.of())),
                List.of()), SOURCE);

        assertThat(report.notesForExperience(0).orElseThrow()).contains("startDate");
    }

    @Test
    void doesNotFlagReflowedBulletText() {
        var report = validator.verify(profileWith(List.of(
                new ExtractedProfile.ExtractedExperience(
                        "Acme AB", "Senior Engineer", "2019", "present", true, null,
                        List.of("Built the ingestion pipeline that reduced duplicate postings by 40%"))),
                List.of()), SOURCE);

        assertThat(report.notesForExperience(0)).isEmpty();
    }

    @Test
    void matchingIsCaseAndWhitespaceInsensitive() {
        var report = validator.verify(profileWith(List.of(
                new ExtractedProfile.ExtractedExperience(
                        "acme   ab", "SENIOR ENGINEER", "2019", "present", true, null, List.of())),
                List.of()), SOURCE);

        assertThat(report.notesForExperience(0)).isEmpty();
    }

    @Test
    void verifiesAndFlagsEducationTheSameWay() {
        var report = validator.verify(profileWith(List.of(), List.of(
                new ExtractedProfile.ExtractedEducation("KTH Royal Institute of Technology",
                        "MSc", "Computer Science", "2014", "2019"),
                new ExtractedProfile.ExtractedEducation("Hogwarts",
                        "MSc", "Wizardry", "2014", "2019"))), SOURCE);

        assertThat(report.notesForEducation(0)).isEmpty();
        assertThat(report.notesForEducation(1).orElseThrow()).contains("institution");
    }

    @Test
    void nullAndBlankFieldsAreNotFlagged() {
        var report = validator.verify(profileWith(List.of(
                new ExtractedProfile.ExtractedExperience(
                        "Acme AB", null, null, "", true, null, List.of())),
                List.of()), SOURCE);

        assertThat(report.notesForExperience(0)).isEmpty();
    }

    @Test
    void reportsSeveralOffendingFieldsInOneNote() {
        var report = validator.verify(profileWith(List.of(
                new ExtractedProfile.ExtractedExperience(
                        "Globex", "Chief Wizard", "1999", "present", true, null, List.of())),
                List.of()), SOURCE);

        String note = report.notesForExperience(0).orElseThrow();
        assertThat(note).contains("employer").contains("title").contains("startDate");
    }
}
