package se.caiowain.jobseeker.tailor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.profile.ProfileNotReadyException;
import se.caiowain.jobseeker.profile.domain.CvProfile;
import se.caiowain.jobseeker.profile.domain.ProfileStatus;
import se.caiowain.jobseeker.profile.repo.CvProfileRepository;
import se.caiowain.jobseeker.repo.JobPostingRepository;
import se.caiowain.jobseeker.tailor.domain.ApplicationStatus;
import se.caiowain.jobseeker.tailor.domain.TailoredApplication;
import se.caiowain.jobseeker.tailor.repo.TailoredApplicationRepository;
import se.caiowain.jobseeker.select.NumberedBullet;
import se.caiowain.jobseeker.select.OllamaSelectionClient;
import se.caiowain.jobseeker.select.SelectionGuard;
import se.caiowain.jobseeker.select.SelectionRejectedException;
import se.caiowain.jobseeker.select.SelectionResult;
import se.caiowain.jobseeker.select.SelectionPromptBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class TailoringService {

    private static final Logger log = LoggerFactory.getLogger(TailoringService.class);

    private final TailoredApplicationRepository applications;
    private final JobPostingRepository jobs;
    private final CvProfileRepository profiles;
    private final OllamaSelectionClient selectionClient;
    private final SelectionPromptBuilder prompts;
    private final SelectionGuard guard;
    private final ApplicationAssembler assembler;
    private final int maxBulletsPerRequirement;

    public TailoringService(TailoredApplicationRepository applications,
                            JobPostingRepository jobs,
                            CvProfileRepository profiles,
                            OllamaSelectionClient selectionClient,
                            SelectionPromptBuilder prompts,
                            SelectionGuard guard,
                            ApplicationAssembler assembler,
                            @Value("${jobseeker.select.max-bullets-per-requirement:3}") int maxBulletsPerRequirement) {
        this.applications = applications;
        this.jobs = jobs;
        this.profiles = profiles;
        this.selectionClient = selectionClient;
        this.prompts = prompts;
        this.guard = guard;
        this.assembler = assembler;
        this.maxBulletsPerRequirement = maxBulletsPerRequirement;
    }

    /**
     * {@code noRollbackFor} is load-bearing: the failure path saves a GENERATION_FAILED
     * record and then throws. Under the default rollback-on-RuntimeException rule that
     * save would be discarded, and the failure would vanish instead of being debuggable.
     */
    @Transactional(noRollbackFor = SelectionRejectedException.class)
    public TailoredApplication tailor(Long jobId) {
        JobPosting job = jobs.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("No job with id " + jobId));

        CvProfile profile = profiles.findFirstByOrderByIdDesc()
                .filter(p -> p.getStatus() == ProfileStatus.READY)
                .orElseThrow(() -> new ProfileNotReadyException(
                        "Approve your CV profile before tailoring an application."));

        Optional<TailoredApplication> existing =
                applications.findByJobPostingIdAndCvProfileId(jobId, profile.getId());
        if (existing.isPresent()) {
            if (existing.get().getStatus() == ApplicationStatus.APPROVED) {
                throw new ApplicationAlreadyApprovedException(
                        "This job already has an approved application. Discard it first.");
            }
            applications.delete(existing.get());
            applications.flush();
        }

        List<NumberedBullet> bullets = prompts.numberBullets(profile);
        if (bullets.isEmpty()) {
            throw new ProfileNotReadyException(
                    "Your profile has no experience bullets to match against.");
        }

        SelectionGuard.GuardVerdict lastVerdict = null;
        SelectionResult lastResult = null;

        // One retry: small models occasionally quote loosely on the first pass.
        for (int attempt = 1; attempt <= 2; attempt++) {
            SelectionResult result = selectionClient.select(job.getDescription(), bullets);
            SelectionGuard.GuardVerdict verdict =
                    guard.check(result, job.getDescription(), bullets.size(), maxBulletsPerRequirement);
            lastResult = result;
            lastVerdict = verdict;

            if (verdict.accepted()) {
                TailoredApplication application = assembler.assemble(
                        result, verdict, bullets, job, profile,
                        selectionClient.modelName(), String.valueOf(result));
                return applications.save(application);
            }
            log.warn("Tailoring attempt {} rejected for job {}: {}",
                    attempt, jobId, verdict.violations());
        }

        TailoredApplication failed = new TailoredApplication();
        failed.setJobPosting(job);
        failed.setCvProfile(profile);
        failed.setStatus(ApplicationStatus.GENERATION_FAILED);
        failed.setModelUsed(selectionClient.modelName());
        failed.setGeneratedAt(Instant.now());
        failed.setRawModelOutput(String.valueOf(lastResult));
        applications.save(failed);
        applications.flush();

        throw new SelectionRejectedException(
                "The model's selection failed validation twice.", lastVerdict.violations());
    }

    @Transactional
    public TailoredApplication discard(Long id) {
        TailoredApplication app = require(id);
        app.setStatus(ApplicationStatus.DISCARDED);
        return applications.save(app);
    }

    @Transactional
    public TailoredApplication saveLetter(Long id, String prose) {
        TailoredApplication app = require(id);
        app.setLetterProse(prose);
        return applications.save(app);
    }

    private TailoredApplication require(Long id) {
        return applications.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("No application with id " + id));
    }
}
