package se.caiowain.jobseeker.fit;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.fit.domain.JobTriage;
import se.caiowain.jobseeker.fit.domain.TriageState;
import se.caiowain.jobseeker.fit.repo.JobTriageRepository;
import se.caiowain.jobseeker.repo.JobPostingRepository;

import java.time.Instant;

@Service
public class TriageService {

    private final JobTriageRepository triages;
    private final JobPostingRepository jobs;

    public TriageService(JobTriageRepository triages, JobPostingRepository jobs) {
        this.triages = triages;
        this.jobs = jobs;
    }

    /** Records or replaces your decision about one posting. One row per posting. */
    @Transactional
    public JobTriage decide(Long jobId, TriageState state, String note) {
        JobPosting job = jobs.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("No job with id " + jobId));

        JobTriage decision = triages.findByJobPostingId(jobId).orElseGet(() -> {
            JobTriage fresh = new JobTriage();
            fresh.setJobPosting(job);
            return fresh;
        });

        decision.setState(state);
        decision.setNote(note);
        decision.setDecidedAt(Instant.now());
        return triages.save(decision);
    }
}
