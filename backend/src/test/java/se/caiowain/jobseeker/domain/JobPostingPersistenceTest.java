package se.caiowain.jobseeker.domain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.repo.JobPostingRepository;
import se.caiowain.jobseeker.repo.JobPostingSourceRepository;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class JobPostingPersistenceTest extends AbstractIntegrationTest {

    @Autowired JobPostingRepository postings;
    @Autowired JobPostingSourceRepository sources;

    @Test
    void persistsPostingWithTwoSourceRows() {
        JobPosting job = new JobPosting();
        job.setFingerprint("fp-test-1");
        job.setCanonicalUrl("https://example.com/jobs/1");
        job.setTitle("Java Developer");
        job.setEmployerName("Acme AB");
        job.setAtsVendor(AtsVendor.TEAMTAILOR);
        job.setStatus(JobStatus.DISCOVERED);
        job.setFirstSeenAt(Instant.now());
        job.setLastSeenAt(Instant.now());
        postings.save(job);

        JobPostingSource a = new JobPostingSource();
        a.setJobPosting(job);
        a.setSource(SourceId.JOBTECH);
        a.setSourceAdId("31404250");
        a.setFetchedAt(Instant.now());
        sources.save(a);

        JobPostingSource b = new JobPostingSource();
        b.setJobPosting(job);
        b.setSource(SourceId.TEAMTAILOR);
        b.setSourceAdId("49f687e1");
        b.setFetchedAt(Instant.now());
        sources.save(b);

        assertThat(postings.findByFingerprint("fp-test-1")).hasSize(1);
        assertThat(sources.findBySourceAndSourceAdId(SourceId.JOBTECH, "31404250")).isPresent();
        assertThat(sources.findBySourceAndSourceAdId(SourceId.TEAMTAILOR, "49f687e1")).isPresent();
    }
}
