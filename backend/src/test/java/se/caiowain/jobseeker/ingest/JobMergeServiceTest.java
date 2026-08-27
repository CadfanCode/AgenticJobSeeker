package se.caiowain.jobseeker.ingest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.domain.AtsVendor;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.domain.SourceId;
import se.caiowain.jobseeker.repo.JobPostingRepository;
import se.caiowain.jobseeker.repo.JobPostingSourceRepository;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class JobMergeServiceTest extends AbstractIntegrationTest {

    @Autowired JobMergeService merge;
    @Autowired JobPostingRepository postings;
    @Autowired JobPostingSourceRepository sources;

    private static final String ORG = "5591754279";
    private static final String TITLE = "Senior Fullstackutvecklare Java/Angular";
    private static final String DESC =
            "Om foretaget Hos Avaron far du tryggheten i en fast anstallning kombinerat med variationen";

    @BeforeEach
    void clean() {
        sources.deleteAll();
        postings.deleteAll();
    }

    private RawJob jobTechAd() {
        return new RawJob("31404250",
                "https://arbetsformedlingen.se/platsbanken/annonser/31404250",
                TITLE, "Avaron AB", ORG, "Norrkoping", DESC, "sv",
                "https://jobs.avaron.se/jobs/8278099-senior-fullstackutvecklare-java-angular/applications/new?promotion=2165239-arbetsformedlingen",
                Instant.parse("2026-08-27T09:14:18Z"), null, "{\"src\":\"jobtech\"}");
    }

    private RawJob teamtailorAd() {
        return new RawJob("49f687e1-ceb1-4701-a5a0-14baf0f77d1d",
                "https://jobs.avaron.se/jobs/8278099-senior-fullstackutvecklare-java-angular",
                TITLE, "Avaron AB", ORG, "Norrkoping",
                DESC + " av att arbeta ute hos olika kunder. Vi tillsatter specialister inom allt fran teknik.",
                "sv",
                "https://jobs.avaron.se/jobs/8278099-senior-fullstackutvecklare-java-angular/applications/new",
                Instant.parse("2026-08-26T14:56:37Z"), null, "{\"src\":\"teamtailor\"}");
    }

    @Test
    void firstSightingCreatesPosting() {
        assertThat(merge.ingest(SourceId.JOBTECH, jobTechAd(), AtsVendor.TEAMTAILOR))
                .isEqualTo(MergeOutcome.CREATED);
        assertThat(postings.count()).isEqualTo(1);
        assertThat(sources.count()).isEqualTo(1);
    }

    @Test
    void sameSourceTwiceIsUnchanged() {
        merge.ingest(SourceId.JOBTECH, jobTechAd(), AtsVendor.TEAMTAILOR);
        assertThat(merge.ingest(SourceId.JOBTECH, jobTechAd(), AtsVendor.TEAMTAILOR))
                .isEqualTo(MergeOutcome.UNCHANGED);
        assertThat(postings.count()).isEqualTo(1);
        assertThat(sources.count()).isEqualTo(1);
    }

    @Test
    void crossSourceDuplicateMergesIntoOnePostingWithTwoSources() {
        merge.ingest(SourceId.JOBTECH, jobTechAd(), AtsVendor.TEAMTAILOR);
        assertThat(merge.ingest(SourceId.TEAMTAILOR, teamtailorAd(), AtsVendor.TEAMTAILOR))
                .isEqualTo(MergeOutcome.MERGED);

        assertThat(postings.count()).isEqualTo(1);
        assertThat(sources.count()).isEqualTo(2);

        JobPosting job = postings.findAll().getFirst();
        assertThat(sources.findByJobPostingId(job.getId())).hasSize(2);
    }

    @Test
    void mergeKeepsTheRicherDescription() {
        merge.ingest(SourceId.JOBTECH, jobTechAd(), AtsVendor.TEAMTAILOR);
        merge.ingest(SourceId.TEAMTAILOR, teamtailorAd(), AtsVendor.TEAMTAILOR);

        JobPosting job = postings.findAll().getFirst();
        assertThat(job.getDescription()).isEqualTo(teamtailorAd().description());
        assertThat(job.getDescription().length()).isGreaterThan(DESC.length());
    }

    @Test
    void mergeStripsTrackingFromApplyUrl() {
        merge.ingest(SourceId.JOBTECH, jobTechAd(), AtsVendor.TEAMTAILOR);
        JobPosting job = postings.findAll().getFirst();
        assertThat(job.getApplyUrl()).doesNotContain("promotion=");
    }

    @Test
    void mergeRefreshesLastSeenButKeepsFirstSeen() {
        merge.ingest(SourceId.JOBTECH, jobTechAd(), AtsVendor.TEAMTAILOR);
        Instant firstSeen = postings.findAll().getFirst().getFirstSeenAt();

        merge.ingest(SourceId.TEAMTAILOR, teamtailorAd(), AtsVendor.TEAMTAILOR);
        JobPosting job = postings.findAll().getFirst();

        assertThat(job.getFirstSeenAt()).isEqualTo(firstSeen);
        assertThat(job.getLastSeenAt()).isAfterOrEqualTo(firstSeen);
    }

    @Test
    void differentJobsDoNotMerge() {
        merge.ingest(SourceId.JOBTECH, jobTechAd(), AtsVendor.TEAMTAILOR);
        RawJob other = new RawJob("999", "https://x.se/999", "Sjukskoterska", "Vard AB",
                "1111111111", "Malmo", "Helt annat jobb", "sv", "https://x.se/apply", null, null, "{}");
        assertThat(merge.ingest(SourceId.JOBTECH, other, AtsVendor.OTHER))
                .isEqualTo(MergeOutcome.CREATED);
        assertThat(postings.count()).isEqualTo(2);
    }
}
