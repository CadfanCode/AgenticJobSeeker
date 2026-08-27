package se.caiowain.jobseeker.ingest;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchCriteriaSpecTest {

    private RawJob job(String title, String description, String municipality) {
        return new RawJob("id", "https://x.se/1", title, "Acme AB", "5591754279",
                municipality, description, "sv", "https://x.se/apply", null, null, "{}");
    }

    @Test
    void matchesWhenAnyKeywordAppearsInTitle() {
        SearchCriteriaSpec spec = new SearchCriteriaSpec(
                "java", "java utvecklare", List.of(), List.of(), List.of());
        assertThat(spec.matchesLocally(job("Senior Java Developer", "irrelevant", "Stockholm"))).isTrue();
    }

    @Test
    void matchesWhenKeywordAppearsOnlyInDescription() {
        SearchCriteriaSpec spec = new SearchCriteriaSpec(
                "java", "java", List.of(), List.of(), List.of());
        assertThat(spec.matchesLocally(job("Systemutvecklare", "Vi soker en Java-utvecklare", "Stockholm"))).isTrue();
    }

    @Test
    void doesNotMatchWhenNoKeywordPresent() {
        SearchCriteriaSpec spec = new SearchCriteriaSpec(
                "java", "java", List.of(), List.of(), List.of());
        assertThat(spec.matchesLocally(job("Sjukskoterska", "Vard och omsorg", "Stockholm"))).isFalse();
    }

    @Test
    void municipalityFilterAppliesWhenPresent() {
        SearchCriteriaSpec spec = new SearchCriteriaSpec(
                "sthlm", "java", List.of(), List.of("Stockholm"), List.of());
        assertThat(spec.matchesLocally(job("Java Developer", "d", "Stockholm"))).isTrue();
        assertThat(spec.matchesLocally(job("Java Developer", "d", "Malmo"))).isFalse();
    }

    @Test
    void emptyQueryMatchesEverything() {
        SearchCriteriaSpec spec = new SearchCriteriaSpec(
                "all", "", List.of(), List.of(), List.of());
        assertThat(spec.matchesLocally(job("Anything", "at all", "Kiruna"))).isTrue();
    }

    @Test
    void matchingIsDiacriticAndCaseInsensitive() {
        SearchCriteriaSpec spec = new SearchCriteriaSpec(
                "sys", "systemutvecklare", List.of(), List.of(), List.of());
        assertThat(spec.matchesLocally(job("SYSTEMUTVECKLARE till Stockholm", "d", "Stockholm"))).isTrue();
    }
}
