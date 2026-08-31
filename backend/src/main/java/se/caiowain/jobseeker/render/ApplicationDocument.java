package se.caiowain.jobseeker.render;

import se.caiowain.jobseeker.profile.domain.CvEducation;
import se.caiowain.jobseeker.profile.domain.CvExperience;
import se.caiowain.jobseeker.profile.domain.CvExperienceBullet;
import se.caiowain.jobseeker.profile.domain.CvProfile;
import se.caiowain.jobseeker.profile.domain.CvSkill;
import se.caiowain.jobseeker.tailor.domain.ApplicationEvidence;
import se.caiowain.jobseeker.tailor.domain.ApplicationRequirement;
import se.caiowain.jobseeker.tailor.domain.TailoredApplication;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Stored rows to what a reader sees. Pure: no Spring, no I/O, no HTML.
 *
 * <p>The CV keeps <b>every</b> experience and <b>every</b> bullet, ordering the ones the model
 * matched to a requirement ahead of the rest within each job. Filtering to matched bullets
 * alone would drop real work history an employer expects to see, and the ranking the model
 * produced is not persisted — {@code ApplicationAssembler} receives {@code rankedBulletIds}
 * and discards them. Ordering expresses relevance without hiding anything.
 */
public class ApplicationDocument {

    public CvContent cv(TailoredApplication application, CvProfile profile) {
        Matched matched = matchedBullets(application);

        List<CvContent.Experience> experiences = new ArrayList<>();
        for (CvExperience experience : profile.getExperiences()) {
            List<String> first = new ArrayList<>();
            List<String> rest = new ArrayList<>();
            for (CvExperienceBullet bullet : experience.getBullets()) {
                (matched.contains(bullet) ? first : rest).add(bullet.getText());
            }
            first.addAll(rest);

            experiences.add(new CvContent.Experience(
                    experience.getEmployer(), experience.getTitle(),
                    experience.getStartDate(), experience.getEndDate(), experience.isCurrent(),
                    experience.getLocation(), List.copyOf(first)));
        }

        List<String> skills = profile.getSkills().stream().map(CvSkill::getName).toList();

        List<CvContent.Education> education = new ArrayList<>();
        for (CvEducation entry : profile.getEducation()) {
            education.add(new CvContent.Education(entry.getInstitution(), entry.getDegree(),
                    entry.getFieldOfStudy(), entry.getStartDate(), entry.getEndDate()));
        }

        return new CvContent(profile.getFullName(), profile.getHeadline(), profile.getEmail(),
                profile.getPhone(), profile.getLocation(), profile.getSummary(),
                skills, List.copyOf(experiences), List.copyOf(education));
    }

    public LetterContent letter(TailoredApplication application, CvProfile profile, LocalDate date) {
        String body = application.getLetterProse() == null ? "" : application.getLetterProse();
        return new LetterContent(
                profile.getFullName(), profile.getEmail(), profile.getPhone(),
                profile.getLocation(),
                application.getJobPosting().getEmployerName(),
                application.getJobPosting().getTitle(),
                date, body);
    }

    /**
     * Which bullets the model cited. Matched by id where the evidence still carries one, and
     * by exact text otherwise — the foreign key is nullable by design in Slice 2b, so the
     * snapshot is the fallback.
     */
    private static Matched matchedBullets(TailoredApplication application) {
        Set<Long> ids = new HashSet<>();
        Set<String> texts = new HashSet<>();
        for (ApplicationRequirement requirement : application.getRequirements()) {
            for (ApplicationEvidence evidence : requirement.getEvidence()) {
                if (evidence.getCvExperienceBulletId() != null) {
                    ids.add(evidence.getCvExperienceBulletId());
                }
                if (evidence.getBulletText() != null) {
                    texts.add(evidence.getBulletText());
                }
            }
        }
        return new Matched(ids, texts);
    }

    private record Matched(Set<Long> ids, Set<String> texts) {
        boolean contains(CvExperienceBullet bullet) {
            return (bullet.getId() != null && ids.contains(bullet.getId()))
                    || texts.contains(bullet.getText());
        }
    }
}
