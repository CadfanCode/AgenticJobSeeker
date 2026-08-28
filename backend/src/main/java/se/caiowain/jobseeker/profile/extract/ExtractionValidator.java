package se.caiowain.jobseeker.profile.extract;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The hallucination guard.
 *
 * <p>The failure mode worth engineering against is not a mangled layout — it is the model
 * inventing an employer or shifting a date into something plausible, which is exactly the
 * error a person skims past when reviewing their own CV. Every extracted employer, title
 * and date literal must occur in the source text; anything that does not is flagged.
 *
 * <p>The check is deliberately one-directional. It cannot prove the extraction is complete,
 * only that what it produced is present in the source. Detecting omissions is what human
 * review is for.
 *
 * <p>Bullet text is not checked: extraction legitimately reflows and merges wrapped lines,
 * so a substring assertion would produce constant false positives.
 */
public class ExtractionValidator {

    public VerificationReport verify(ExtractedProfile profile, String sourceText) {
        String haystack = fold(sourceText);

        Map<Integer, String> experienceNotes = new LinkedHashMap<>();
        List<ExtractedProfile.ExtractedExperience> experiences =
                profile.experiences() == null ? List.of() : profile.experiences();
        for (int i = 0; i < experiences.size(); i++) {
            var experience = experiences.get(i);
            List<String> offenders = new ArrayList<>();
            checkField("employer", experience.employer(), haystack, offenders);
            checkField("title", experience.title(), haystack, offenders);
            checkField("startDate", experience.startDate(), haystack, offenders);
            checkField("endDate", experience.endDate(), haystack, offenders);
            if (!offenders.isEmpty()) {
                experienceNotes.put(i, note(offenders));
            }
        }

        Map<Integer, String> educationNotes = new LinkedHashMap<>();
        List<ExtractedProfile.ExtractedEducation> education =
                profile.education() == null ? List.of() : profile.education();
        for (int i = 0; i < education.size(); i++) {
            var entry = education.get(i);
            List<String> offenders = new ArrayList<>();
            checkField("institution", entry.institution(), haystack, offenders);
            checkField("degree", entry.degree(), haystack, offenders);
            checkField("fieldOfStudy", entry.fieldOfStudy(), haystack, offenders);
            checkField("startDate", entry.startDate(), haystack, offenders);
            checkField("endDate", entry.endDate(), haystack, offenders);
            if (!offenders.isEmpty()) {
                educationNotes.put(i, note(offenders));
            }
        }

        return new VerificationReport(experienceNotes, educationNotes);
    }

    private void checkField(String fieldName, String value, String haystack, List<String> offenders) {
        if (value == null || value.isBlank()) {
            return;
        }
        String needle = fold(value);
        if (needle.isEmpty()) {
            return;
        }
        if (!haystack.contains(needle)) {
            offenders.add(fieldName + " \"" + value.strip() + "\"");
        }
    }

    private String note(List<String> offenders) {
        return "Not found in the uploaded CV: " + String.join(", ", offenders);
    }

    /** Lowercase, strip diacritics and punctuation, collapse whitespace. */
    private static String fold(String value) {
        if (value == null) {
            return "";
        }
        String normalised = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return normalised.toLowerCase()
                .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]+", " ")
                .strip()
                .replaceAll("\\s+", " ");
    }

    /** Empty note means verified. */
    public record VerificationReport(Map<Integer, String> experienceNotes,
                                     Map<Integer, String> educationNotes) {

        public Optional<String> notesForExperience(int index) {
            return Optional.ofNullable(experienceNotes.get(index));
        }

        public Optional<String> notesForEducation(int index) {
            return Optional.ofNullable(educationNotes.get(index));
        }
    }
}
