package se.caiowain.jobseeker.tailor;

import se.caiowain.jobseeker.domain.JobPosting;
import se.caiowain.jobseeker.profile.domain.CvProfile;
import se.caiowain.jobseeker.tailor.domain.ApplicationEvidence;
import se.caiowain.jobseeker.tailor.domain.ApplicationRequirement;
import se.caiowain.jobseeker.tailor.domain.ApplicationStatus;
import se.caiowain.jobseeker.tailor.domain.TailoredApplication;
import se.caiowain.jobseeker.tailor.select.NumberedBullet;
import se.caiowain.jobseeker.tailor.select.SelectionGuard;
import se.caiowain.jobseeker.tailor.select.SelectionResult;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the application from profile rows. Pure and deterministic.
 *
 * <p>Every string that reaches the employer originates here, from a {@link NumberedBullet}
 * the candidate wrote or a requirement phrase quoted from the ad. The model's integers only
 * choose and order; they never supply text.
 */
public class ApplicationAssembler {

    public TailoredApplication assemble(SelectionResult result,
                                        SelectionGuard.GuardVerdict verdict,
                                        List<NumberedBullet> bullets,
                                        JobPosting job,
                                        CvProfile profile,
                                        String modelUsed,
                                        String rawOutput) {
        Map<Integer, NumberedBullet> byIndex = new LinkedHashMap<>();
        for (NumberedBullet bullet : bullets) {
            byIndex.put(bullet.index(), bullet);
        }

        TailoredApplication application = new TailoredApplication();
        application.setJobPosting(job);
        application.setCvProfile(profile);
        application.setStatus(ApplicationStatus.DRAFT);
        application.setModelUsed(modelUsed);
        application.setGeneratedAt(Instant.now());
        application.setRawModelOutput(rawOutput);

        List<SelectionResult.RequirementSelection> selections =
                result.requirements() == null ? List.of() : result.requirements();

        int withEvidence = 0;
        for (int i = 0; i < selections.size(); i++) {
            var selection = selections.get(i);

            ApplicationRequirement requirement = new ApplicationRequirement();
            requirement.setText(selection.text());
            requirement.setOrdinal(i);
            requirement.setOverBroad(verdict.isOverBroad(i));
            application.addRequirement(requirement);

            List<Integer> ids = selection.bulletIds() == null ? List.of() : selection.bulletIds();
            int ordinal = 0;
            for (Integer id : ids) {
                NumberedBullet bullet = byIndex.get(id);
                if (bullet == null) {
                    // Never fabricate a fallback. The guard rejects this upstream; if one
                    // slips through, dropping it is the only honest response.
                    continue;
                }
                ApplicationEvidence evidence = new ApplicationEvidence();
                evidence.setCvExperienceBulletId(bullet.bulletId());
                evidence.setBulletText(bullet.text());
                evidence.setOrdinal(ordinal++);
                requirement.addEvidence(evidence);
            }
            if (!requirement.getEvidence().isEmpty()) {
                withEvidence++;
            }
        }

        application.setCoveragePercent(
                selections.isEmpty() ? 0 : Math.round(withEvidence * 100f / selections.size()));
        return application;
    }
}
