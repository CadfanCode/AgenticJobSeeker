package se.caiowain.jobseeker.ingest;

import se.caiowain.jobseeker.domain.SearchCriteria;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Runtime view of a persisted {@link SearchCriteria}, plus the local matching used by
 * feed-based sources that offer no server-side query.
 */
public record SearchCriteriaSpec(
        String name,
        String query,
        List<String> municipalityCodes,
        List<String> municipalityNames,
        List<String> occupationFieldCodes
) {

    public static SearchCriteriaSpec from(SearchCriteria entity) {
        return new SearchCriteriaSpec(
                entity.getName(),
                entity.getQuery() == null ? "" : entity.getQuery(),
                split(entity.getMunicipalityCodes()),
                split(entity.getMunicipalityNames()),
                split(entity.getOccupationFieldCodes()));
    }

    private static List<String> split(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /** Client-side filter for whole-catalogue feeds. */
    public boolean matchesLocally(RawJob job) {
        if (!municipalityNames.isEmpty()) {
            String jobMunicipality = fold(job.municipality());
            boolean municipalityMatch = municipalityNames.stream()
                    .anyMatch(m -> fold(m).equals(jobMunicipality));
            if (!municipalityMatch) {
                return false;
            }
        }
        if (query == null || query.isBlank()) {
            return true;
        }
        String haystack = fold(job.title()) + " " + fold(job.description());
        return Arrays.stream(query.split("\\s+"))
                .map(SearchCriteriaSpec::fold)
                .filter(k -> !k.isEmpty())
                .anyMatch(haystack::contains);
    }

    /** Lowercase, strip diacritics, so "Malmo" matches "Malmoe". */
    private static String fold(String value) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return normalized.toLowerCase();
    }
}
