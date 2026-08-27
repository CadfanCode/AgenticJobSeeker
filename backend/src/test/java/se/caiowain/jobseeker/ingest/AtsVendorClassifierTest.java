package se.caiowain.jobseeker.ingest;

import org.junit.jupiter.api.Test;
import se.caiowain.jobseeker.domain.AtsVendor;

import static org.assertj.core.api.Assertions.assertThat;

class AtsVendorClassifierTest {

    private final AtsVendorClassifier classifier = new AtsVendorClassifier();

    @Test
    void detectsTeamtailorFromReference() {
        assertThat(classifier.classify("https://jobs.example.se/jobs/1", "teamtailor-8161354-abc"))
                .isEqualTo(AtsVendor.TEAMTAILOR);
    }

    @Test
    void detectsTeamtailorFromCustomDomainUrlShape() {
        assertThat(classifier.classify(
                "https://jobs.avaron.se/jobs/8278099-senior-fullstackutvecklare-java-angular/applications/new",
                null))
                .isEqualTo(AtsVendor.TEAMTAILOR);
    }

    @Test
    void detectsVarbiFromHost() {
        assertThat(classifier.classify(
                "https://transportstyrelsen.varbi.com/se/what:job/jobID:962618/type:job/where:125/apply:1", null))
                .isEqualTo(AtsVendor.VARBI);
    }

    @Test
    void detectsReachmeeFromHost() {
        assertThat(classifier.classify("https://web103.reachmee.com/ext/I003/584/main?site=19", null))
                .isEqualTo(AtsVendor.REACHMEE);
    }

    @Test
    void fallsBackToOtherForUnknownDomains() {
        assertThat(classifier.classify("https://careers.randomcompany.se/apply", null))
                .isEqualTo(AtsVendor.OTHER);
    }

    @Test
    void handlesNullApplyUrl() {
        assertThat(classifier.classify(null, null)).isEqualTo(AtsVendor.OTHER);
    }

    @Test
    void buildsTeamtailorFeedUrl() {
        assertThat(classifier.feedUrlFor(AtsVendor.TEAMTAILOR, "jobs.avaron.se"))
                .contains("https://jobs.avaron.se/jobs.json");
    }

    @Test
    void buildsVarbiFeedUrl() {
        assertThat(classifier.feedUrlFor(AtsVendor.VARBI, "transportstyrelsen.varbi.com"))
                .contains("https://transportstyrelsen.varbi.com/what:rssfeed/");
    }

    @Test
    void noFeedUrlForNonEnumerableVendors() {
        assertThat(classifier.feedUrlFor(AtsVendor.REACHMEE, "web103.reachmee.com")).isEmpty();
        assertThat(classifier.feedUrlFor(AtsVendor.OTHER, "x.se")).isEmpty();
    }

    @Test
    void extractsHostAndDropsWww() {
        assertThat(classifier.hostOf("https://WWW.Example.SE/path")).contains("example.se");
    }
}
