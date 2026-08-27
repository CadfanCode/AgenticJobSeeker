package se.caiowain.jobseeker.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import se.caiowain.jobseeker.domain.AtsVendor;
import se.caiowain.jobseeker.domain.IngestRun;
import se.caiowain.jobseeker.domain.RunStatus;
import se.caiowain.jobseeker.repo.IngestRunRepository;
import se.caiowain.jobseeker.repo.SearchCriteriaRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the configured sources. Per-source isolation is the contract: one adapter
 * throwing must never prevent the others from running or from recording their results.
 */
@Service
public class IngestOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(IngestOrchestrator.class);

    private final List<JobSource> sources;
    private final JobMergeService merge;
    private final AtsVendorClassifier classifier;
    private final SearchCriteriaRepository criteriaRepository;
    private final IngestRunRepository runs;

    public IngestOrchestrator(List<JobSource> sources,
                              JobMergeService merge,
                              AtsVendorClassifier classifier,
                              SearchCriteriaRepository criteriaRepository,
                              IngestRunRepository runs) {
        this.sources = sources;
        this.merge = merge;
        this.classifier = classifier;
        this.criteriaRepository = criteriaRepository;
        this.runs = runs;
    }

    /** Runs every registered source with the enabled criteria from the database. */
    public List<IngestRun> runAll() {
        return runAll(sources);
    }

    public List<IngestRun> runAll(List<JobSource> sourcesToRun) {
        List<SearchCriteriaSpec> specs = loadCriteria();
        List<IngestRun> results = new ArrayList<>();
        for (JobSource source : sourcesToRun) {
            results.add(runOne(source, specs));
        }
        return results;
    }

    public List<SearchCriteriaSpec> loadCriteria() {
        return criteriaRepository.findByEnabledTrue().stream()
                .map(SearchCriteriaSpec::from)
                .toList();
    }

    public IngestRun runOne(JobSource source, List<SearchCriteriaSpec> criteria) {
        IngestRun run = new IngestRun();
        run.setSource(source.id());
        run.setStartedAt(Instant.now());
        run.setStatus(RunStatus.RUNNING);
        runs.save(run);

        try {
            List<RawJob> fetched = source.fetch(criteria);
            run.setFetched(fetched.size());

            int created = 0;
            int merged = 0;
            int errors = 0;

            for (RawJob raw : fetched) {
                try {
                    AtsVendor vendor = classifier.classify(raw.applyUrl(), null);
                    MergeOutcome outcome = merge.ingest(source.id(), raw, vendor);
                    if (outcome == MergeOutcome.CREATED) {
                        created++;
                    } else if (outcome == MergeOutcome.MERGED) {
                        merged++;
                    }
                } catch (RuntimeException e) {
                    // A single malformed ad must not abandon the rest of the batch.
                    errors++;
                    log.warn("Failed to ingest ad {} from {}: {}",
                            raw.sourceAdId(), source.id(), e.getMessage());
                }
            }

            run.setCreated(created);
            run.setMerged(merged);
            run.setErrors(errors);
            run.setStatus(RunStatus.COMPLETED);
        } catch (Exception e) {
            log.error("Source {} failed entirely: {}", source.id(), e.getMessage());
            run.setStatus(RunStatus.FAILED);
            run.setMessage(e.getMessage());
        } finally {
            run.setFinishedAt(Instant.now());
            runs.save(run);
        }
        return run;
    }
}
