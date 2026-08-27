package se.caiowain.jobseeker.ingest;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JobIdentityTest {

    @Test
    void stripsTrackingParamsFromApplyUrl() {
        String tagged = "https://jobs.avaron.se/jobs/8278099-senior-fullstackutvecklare-java-angular/applications/new?promotion=2165239-arbetsformedlingen";
        String clean = "https://jobs.avaron.se/jobs/8278099-senior-fullstackutvecklare-java-angular/applications/new";
        assertThat(JobIdentity.canonicalUrl(tagged)).isEqualTo(JobIdentity.canonicalUrl(clean));
    }

    @Test
    void normalizesHostCasingWwwAndTrailingSlash() {
        assertThat(JobIdentity.canonicalUrl("https://WWW.Sverigedev.se/jobb/123/"))
                .isEqualTo(JobIdentity.canonicalUrl("https://sverigedev.se/jobb/123"));
    }

    @Test
    void stripsUtmAndSessionParamsButKeepsMeaningfulOnes() {
        assertThat(JobIdentity.canonicalUrl("https://x.se/j?utm_source=a&id=7&fbclid=z"))
                .isEqualTo("https://x.se/j?id=7");
    }

    @Test
    void handlesMalformedUrlWithoutThrowing() {
        assertThat(JobIdentity.canonicalUrl("not a url")).isEqualTo("not a url");
        assertThat(JobIdentity.canonicalUrl(null)).isNull();
    }

    @Test
    void normalizeTitleIsCaseAndPunctuationInsensitive() {
        assertThat(JobIdentity.normalizeTitle("Senior Fullstackutvecklare  Java/Angular"))
                .isEqualTo(JobIdentity.normalizeTitle("senior fullstackutvecklare java angular"));
    }

    @Test
    void fingerprintIsStableAcrossSourcesForTheSameJob() {
        String a = JobIdentity.fingerprint("5591754279", "Senior Fullstackutvecklare Java/Angular",
                "Om foretaget Hos Avaron far du tryggheten i en fast anstallning");
        String b = JobIdentity.fingerprint("5591754279", "senior fullstackutvecklare java/angular",
                "Om foretaget Hos Avaron far du tryggheten i en fast anstallning   ");
        assertThat(a).isEqualTo(b);
    }

    @Test
    void fingerprintDiffersForDifferentJobs() {
        String a = JobIdentity.fingerprint("5591754279", "Java Developer", "Build services");
        String b = JobIdentity.fingerprint("5591754279", "Python Developer", "Build services");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void fingerprintIsSha256Hex() {
        assertThat(JobIdentity.fingerprint("1", "t", "d")).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void fingerprintToleratesNullOrgNumber() {
        assertThat(JobIdentity.fingerprint(null, "Java Developer", "Build services")).hasSize(64);
    }
}
