package se.caiowain.jobseeker.fit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.fit.domain.JobPreferences;
import se.caiowain.jobseeker.fit.domain.JobPrescreen;
import se.caiowain.jobseeker.fit.repo.JobPreferencesRepository;
import se.caiowain.jobseeker.fit.repo.JobPrescreenRepository;
import se.caiowain.jobseeker.profile.ProfileNotReadyException;
import se.caiowain.jobseeker.profile.domain.CvProfile;
import se.caiowain.jobseeker.profile.domain.ProfileStatus;
import se.caiowain.jobseeker.profile.repo.CvProfileRepository;
import se.caiowain.jobseeker.repo.JobPostingRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Rebuilds the whole prescreen table.
 *
 * <p>Truncate-and-rebuild rather than upsert, because the inputs are global: changing one
 * preference or approving a new CV invalidates every row at once, and a partial update would
 * leave the list a mix of two answers.
 *
 * <p>It writes only to {@code job_prescreen}. Triage decisions and deep scores live in their
 * own tables with no foreign key into this one, so a rebuild cannot reach them.
 */
@Service
public class PrescreenService {

    private static final Logger log = LoggerFactory.getLogger(PrescreenService.class);

    private final JobPostingRepository jobs;
    private final JobPrescreenRepository prescreens;
    private final CvProfileRepository profiles;
    private final JobPreferencesRepository preferences;
    private final SkillMatchQuery skillMatches;
    private final GateEvaluator gates;

    public PrescreenService(JobPostingRepository jobs,
                            JobPrescreenRepository prescreens,
                            CvProfileRepository profiles,
                            JobPreferencesRepository preferences,
                            SkillMatchQuery skillMatches,
                            GateEvaluator gates) {
        this.jobs = jobs;
        this.prescreens = prescreens;
        this.profiles = profiles;
        this.preferences = preferences;
        this.skillMatches = skillMatches;
        this.gates = gates;
    }

    @Transactional
    public PrescreenSummary run() {
        CvProfile profile = profiles.findFirstByOrderByIdDesc()
                .filter(p -> p.getStatus() == ProfileStatus.READY)
                .orElseThrow(() -> new ProfileNotReadyException(
                        "Approve your CV profile before ranking jobs."));

        JobPreferences prefs = preferences.findSingleton();
        Map<Long, List<String>> matches = skillMatches.matchesByJob(profile.getId());
        Instant now = Instant.now();

        prescreens.deleteAllInBatch();
        prescreens.flush();

        List<JobPrescreen> rows = new ArrayList<>();
        int withMatches = 0;

        for (JobPosting job : jobs.findAll()) {
            List<String> skills = matches.getOrDefault(job.getId(), List.of());
            if (!skills.isEmpty()) {
                withMatches++;
            }
            GateOutcome outcome = gates.evaluate(job, prefs, now);

            JobPrescreen row = new JobPrescreen();
            row.setJobPosting(job);
            row.setCvProfile(profile);
            row.setMatchedSkillCount(skills.size());
            row.setMatchedSkills(String.join(", ", skills));
            row.setLanguageGate(outcome.languageGate());
            row.setLanguageNote(outcome.languageNote());
            row.setLocationGate(outcome.locationGate());
            row.setDeadlinePassed(outcome.deadlinePassed());
            row.setComputedAt(now);
            rows.add(row);
        }

        prescreens.saveAll(rows);
        return new PrescreenSummary(rows.size(), withMatches, now);
    }

    /**
     * For callers that want the ranking refreshed but must not fail without it —
     * {@code IngestController} calls this after the ingest has already committed, so nothing
     * here may turn a successful ingest into a reported failure. Swallows everything by
     * design, not just {@link ProfileNotReadyException}: a missing preferences row
     * ({@code IllegalStateException}), a lock timeout, or any other database error while
     * rebuilding the ranking must be logged and absorbed here rather than propagated, for the
     * same reason. Returns empty whenever nothing could be ranked, whatever the cause.
     *
     * <p>{@code @Transactional} here, not just on {@link #run()}: {@code this.run()} is a
     * self-invocation, so it never passes through {@code run()}'s own proxy advice. Without a
     * transaction on this method too, {@code run()} would execute with none at all — its
     * {@code deleteAllInBatch()} and {@code saveAll(rows)} would each commit separately, and a
     * crash between them would leave {@code job_prescreen} truncated rather than rebuilt. With
     * the transaction opened here, the self-invoked {@code run()} body simply participates in
     * it, and catching broadly inside it is still safe: none of these exceptions cross a
     * transactional proxy boundary, so none of them can mark this transaction rollback-only —
     * and a genuine database failure (the lock timeout, say) aborts the underlying transaction
     * on its own regardless of what Java code here catches.
     */
    @Transactional
    public Optional<PrescreenSummary> runQuietly() {
        try {
            return Optional.of(run());
        } catch (Exception e) {
            log.warn("Skipping the ranking after ingest: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }
}
