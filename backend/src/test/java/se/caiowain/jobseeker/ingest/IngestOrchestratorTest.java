package se.caiowain.jobseeker.ingest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import se.caiowain.jobseeker.AbstractIntegrationTest;
import se.caiowain.jobseeker.domain.IngestRun;
import se.caiowain.jobseeker.domain.RunStatus;
import se.caiowain.jobseeker.domain.SourceId;
import se.caiowain.jobseeker.repo.IngestRunRepository;
import se.caiowain.jobseeker.repo.JobPostingRepository;
import se.caiowain.jobseeker.repo.JobPostingSourceRepository;
import se.caiowain.jobseeker.repo.SearchCriteriaRepository;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class IngestOrchestratorTest extends AbstractIntegrationTest {

    @Autowired IngestOrchestrator orchestrator;
    @Autowired JobPostingRepository postings;
    @Autowired JobPostingSourceRepository sources;
    @Autowired IngestRunRepository runs;
    @Autowired SearchCriteriaRepository criteria;

    @BeforeEach
    void clean() {
        sources.deleteAll();
        postings.deleteAll();
        runs.deleteAll();
    }

    private JobSource stubSource(SourceId id, List<RawJob> jobs) {
        return new JobSource() {
            @Override public SourceId id() { return id; }
            @Override public List<RawJob> fetch(List<SearchCriteriaSpec> c) { return jobs; }
        };
    }

    private JobSource explodingSource(SourceId id) {
        return new JobSource() {
            @Override public SourceId id() { return id; }
            @Override public List<RawJob> fetch(List<SearchCriteriaSpec> c) {
                throw new IllegalStateException("source is down");
            }
        };
    }

    private RawJob job(String adId, String title) {
        return new RawJob(adId, "https://x.se/" + adId, title, "Acme AB", "5591754279",
                "Stockholm", "Description for " + title, "sv",
                "https://jobs.acme.se/jobs/1-a/applications/new",
                Instant.now(), null, "{}");
    }

    @Test
    void recordsOneRunPerSourceWithCounts() {
        IngestRun run = orchestrator.runOne(
                stubSource(SourceId.JOBTECH, List.of(job("1", "Java Developer"), job("2", "Python Developer"))),
                List.of(new SearchCriteriaSpec("all", "", List.of(), List.of(), List.of())));

        assertThat(run.getSource()).isEqualTo(SourceId.JOBTECH);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.getFetched()).isEqualTo(2);
        assertThat(run.getFinishedAt()).isNotNull();
        assertThat(postings.count()).isEqualTo(2);
    }

    @Test
    void countsMergesSeparatelyFromCreates() {
        var spec = List.of(new SearchCriteriaSpec("all", "", List.of(), List.of(), List.of()));
        orchestrator.runOne(stubSource(SourceId.JOBTECH, List.of(job("1", "Java Developer"))), spec);

        IngestRun second = orchestrator.runOne(
                stubSource(SourceId.TEAMTAILOR, List.of(job("tt-1", "Java Developer"))), spec);

        assertThat(second.getMerged()).isEqualTo(1);
        assertThat(second.getCreated()).isZero();
        assertThat(postings.count()).isEqualTo(1);
        assertThat(sources.count()).isEqualTo(2);
    }

    @Test
    void aFailingSourceIsRecordedAsFailedNotThrown() {
        IngestRun run = orchestrator.runOne(explodingSource(SourceId.VARBI), List.of());

        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getMessage()).contains("source is down");
        assertThat(run.getFinishedAt()).isNotNull();
    }

    @Test
    void runAllIsolatesFailuresBetweenSources() {
        List<IngestRun> results = orchestrator.runAll(List.of(
                stubSource(SourceId.JOBTECH, List.of(job("1", "Java Developer"))),
                explodingSource(SourceId.VARBI),
                stubSource(SourceId.TEAMTAILOR, List.of(job("tt-9", "Kotlin Developer")))));

        assertThat(results).hasSize(3);
        assertThat(results.stream().filter(r -> r.getStatus() == RunStatus.COMPLETED)).hasSize(2);
        assertThat(results.stream().filter(r -> r.getStatus() == RunStatus.FAILED)).hasSize(1);
        assertThat(postings.count()).isEqualTo(2);
    }

    @Test
    void seedCriteriaAreLoadedFromTheDatabase() {
        assertThat(criteria.findByEnabledTrue()).isNotEmpty();
        assertThat(criteria.findByEnabledTrue())
                .anyMatch(c -> c.getName().contains("Stockholm"));
    }
}
