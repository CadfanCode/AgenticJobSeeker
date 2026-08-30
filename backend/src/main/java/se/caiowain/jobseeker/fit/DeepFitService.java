package se.caiowain.jobseeker.fit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.fit.domain.JobDeepFit;
import se.caiowain.jobseeker.fit.domain.JobDeepFitGap;
import se.caiowain.jobseeker.fit.repo.JobDeepFitRepository;
import se.caiowain.jobseeker.profile.ProfileNotReadyException;
import se.caiowain.jobseeker.profile.domain.CvProfile;
import se.caiowain.jobseeker.profile.domain.ProfileStatus;
import se.caiowain.jobseeker.profile.repo.CvProfileRepository;
import se.caiowain.jobseeker.repo.JobPostingRepository;
import se.caiowain.jobseeker.select.Coverage;
import se.caiowain.jobseeker.select.NumberedBullet;
import se.caiowain.jobseeker.select.OllamaSelectionClient;
import se.caiowain.jobseeker.select.SelectionGuard;
import se.caiowain.jobseeker.select.SelectionPromptBuilder;
import se.caiowain.jobseeker.select.SelectionRejectedException;
import se.caiowain.jobseeker.select.SelectionResult;

import java.time.Instant;
import java.util.List;

/**
 * Measures one job against the profile, on demand, in roughly 94 seconds.
 *
 * <p>Reuses the tailoring slice's prompt, client and guards without modification. The only
 * difference is what is kept: a coverage number and the unanswered requirements, rather than
 * an assembled application. Nothing here is persisted unless the guards accept the result —
 * a rejected run leaves no row, so a failure can never be mistaken for a low score.
 */
@Service
public class DeepFitService {

    private static final Logger log = LoggerFactory.getLogger(DeepFitService.class);

    private final JobDeepFitRepository deepFits;
    private final JobPostingRepository jobs;
    private final CvProfileRepository profiles;
    private final OllamaSelectionClient selectionClient;
    private final SelectionPromptBuilder prompts;
    private final SelectionGuard guard;
    private final int maxBulletsPerRequirement;

    public DeepFitService(JobDeepFitRepository deepFits,
                          JobPostingRepository jobs,
                          CvProfileRepository profiles,
                          OllamaSelectionClient selectionClient,
                          SelectionPromptBuilder prompts,
                          SelectionGuard guard,
                          @Value("${jobseeker.select.max-bullets-per-requirement:3}")
                          int maxBulletsPerRequirement) {
        this.deepFits = deepFits;
        this.jobs = jobs;
        this.profiles = profiles;
        this.selectionClient = selectionClient;
        this.prompts = prompts;
        this.guard = guard;
        this.maxBulletsPerRequirement = maxBulletsPerRequirement;
    }

    @Transactional
    public JobDeepFit score(Long jobId) {
        JobPosting job = jobs.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("No job with id " + jobId));

        CvProfile profile = profiles.findFirstByOrderByIdDesc()
                .filter(p -> p.getStatus() == ProfileStatus.READY)
                .orElseThrow(() -> new ProfileNotReadyException(
                        "Approve your CV profile before scoring a job."));

        List<NumberedBullet> bullets = prompts.numberBullets(profile);
        if (bullets.isEmpty()) {
            throw new ProfileNotReadyException(
                    "Your profile has no experience bullets to match against.");
        }

        // Replacing rather than refusing: unlike a tailored application there is no
        // approved prose here to protect, so a re-score is always safe.
        deepFits.findByJobPostingIdAndCvProfileId(jobId, profile.getId())
                .ifPresent(existing -> {
                    deepFits.delete(existing);
                    deepFits.flush();
                });

        SelectionGuard.GuardVerdict lastVerdict = null;

        // One retry, matching the tailoring slice: small models quote loosely on a first pass.
        for (int attempt = 1; attempt <= 2; attempt++) {
            SelectionResult result = selectionClient.select(job.getDescription(), bullets);
            SelectionGuard.GuardVerdict verdict = guard.check(
                    result, job.getDescription(), bullets.size(), maxBulletsPerRequirement);
            lastVerdict = verdict;

            if (verdict.accepted()) {
                return deepFits.save(build(result, job, profile));
            }
            log.warn("Deep fit attempt {} rejected for job {}: {}",
                    attempt, jobId, verdict.violations());
        }

        throw new SelectionRejectedException(
                "The model's selection failed validation twice.", lastVerdict.violations());
    }

    private JobDeepFit build(SelectionResult result, JobPosting job, CvProfile profile) {
        List<SelectionResult.RequirementSelection> requirements = result.requirements();

        JobDeepFit fit = new JobDeepFit();
        fit.setJobPosting(job);
        fit.setCvProfile(profile);
        fit.setRequirementCount(requirements.size());
        fit.setModelUsed(selectionClient.modelName());
        fit.setDeepScoredAt(Instant.now());

        int withEvidence = 0;
        int ordinal = 0;
        for (SelectionResult.RequirementSelection requirement : requirements) {
            boolean supported = requirement.bulletIds() != null
                    && !requirement.bulletIds().isEmpty();
            if (supported) {
                withEvidence++;
            } else {
                JobDeepFitGap gap = new JobDeepFitGap();
                gap.setText(requirement.text());
                gap.setOrdinal(ordinal++);
                fit.addGap(gap);
            }
        }

        fit.setCoveragePercent(Coverage.percent(withEvidence, requirements.size()));
        return fit;
    }
}
