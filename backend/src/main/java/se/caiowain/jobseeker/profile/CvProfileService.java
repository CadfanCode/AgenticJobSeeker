package se.caiowain.jobseeker.profile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import se.caiowain.jobseeker.profile.domain.*;
import se.caiowain.jobseeker.profile.extract.CvProfileExtractor;
import se.caiowain.jobseeker.profile.extract.ExtractedProfile;
import se.caiowain.jobseeker.profile.extract.ExtractionValidator;
import se.caiowain.jobseeker.profile.repo.CvDocumentRepository;
import se.caiowain.jobseeker.profile.repo.CvProfileRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class CvProfileService {

    private static final Logger log = LoggerFactory.getLogger(CvProfileService.class);

    private final CvProfileRepository profiles;
    private final CvDocumentRepository documents;
    private final CvProfileExtractor extractor;
    private final ExtractionValidator validator;

    public CvProfileService(CvProfileRepository profiles, CvDocumentRepository documents,
                            CvProfileExtractor extractor, ExtractionValidator validator) {
        this.profiles = profiles;
        this.documents = documents;
        this.extractor = extractor;
        this.validator = validator;
    }

    public Optional<CvProfile> current() {
        return profiles.findFirstByOrderByIdDesc();
    }

    /** Extract, validate, persist. A failed model call is recorded, never thrown away. */
    @Transactional
    public CvProfile ingest(CvDocument document) {
        CvProfile profile = new CvProfile();
        profile.setCvDocument(document);
        profile.setExtractedAt(Instant.now());
        profile.setModelUsed(extractor.modelName());

        try {
            ExtractedProfile extracted = extractor.extract(document.getExtractedText());
            apply(profile, extracted, document.getExtractedText());
            profile.setStatus(ProfileStatus.NEEDS_REVIEW);
        } catch (CvProfileExtractor.ExtractionFailedException e) {
            log.warn("Extraction failed for document {}: {}", document.getId(), e.getMessage());
            profile.setStatus(ProfileStatus.EXTRACTION_FAILED);
        }
        return profiles.save(profile);
    }

    /** Re-runs extraction from the stored text — no re-upload, no second PDF parse. */
    @Transactional
    public CvProfile reextract() {
        CvProfile existing = current().orElseThrow(
                () -> new IllegalStateException("There is no profile to re-extract"));
        CvDocument document = existing.getCvDocument();
        return ingest(documents.findById(document.getId()).orElse(document));
    }

    @Transactional
    public CvProfile approve() {
        CvProfile profile = current().orElseThrow(
                () -> new IllegalStateException("There is no profile to approve"));
        profile.setStatus(ProfileStatus.READY);
        profile.setReviewedAt(Instant.now());
        return profiles.save(profile);
    }

    /** Replaces the editable content of the current profile with the reviewer's version. */
    @Transactional
    public CvProfile applyCorrections(CvProfile edited) {
        CvProfile profile = current().orElseThrow(
                () -> new IllegalStateException("There is no profile to update"));

        profile.setFullName(edited.getFullName());
        profile.setHeadline(edited.getHeadline());
        profile.setEmail(edited.getEmail());
        profile.setPhone(edited.getPhone());
        profile.setLocation(edited.getLocation());
        profile.setSummary(edited.getSummary());
        profile.setLanguage(edited.getLanguage());

        profile.getExperiences().clear();
        for (CvExperience e : edited.getExperiences()) {
            CvExperience copy = new CvExperience();
            copy.setEmployer(e.getEmployer());
            copy.setTitle(e.getTitle());
            copy.setStartDate(e.getStartDate());
            copy.setEndDate(e.getEndDate());
            copy.setCurrent(e.isCurrent());
            copy.setLocation(e.getLocation());
            copy.setOrdinal(e.getOrdinal());
            copy.setVerified(e.isVerified());
            copy.setVerificationNotes(e.getVerificationNotes());
            profile.addExperience(copy);
            for (CvExperienceBullet b : e.getBullets()) {
                CvExperienceBullet bullet = new CvExperienceBullet();
                bullet.setText(b.getText());
                bullet.setOrdinal(b.getOrdinal());
                copy.addBullet(bullet);
            }
        }

        profile.getEducation().clear();
        for (CvEducation e : edited.getEducation()) {
            CvEducation copy = new CvEducation();
            copy.setInstitution(e.getInstitution());
            copy.setDegree(e.getDegree());
            copy.setFieldOfStudy(e.getFieldOfStudy());
            copy.setStartDate(e.getStartDate());
            copy.setEndDate(e.getEndDate());
            copy.setOrdinal(e.getOrdinal());
            copy.setVerified(e.isVerified());
            copy.setVerificationNotes(e.getVerificationNotes());
            profile.addEducation(copy);
        }

        profile.getSkills().clear();
        for (CvSkill s : edited.getSkills()) {
            CvSkill copy = new CvSkill();
            copy.setName(s.getName());
            copy.setCategory(s.getCategory());
            copy.setOrdinal(s.getOrdinal());
            profile.addSkill(copy);
        }

        return profiles.save(profile);
    }

    /** Maps the model's output onto entities, attaching the validator's findings. */
    private void apply(CvProfile profile, ExtractedProfile extracted, String sourceText) {
        profile.setFullName(extracted.fullName());
        profile.setHeadline(extracted.headline());
        profile.setEmail(extracted.email());
        profile.setPhone(extracted.phone());
        profile.setLocation(extracted.location());
        profile.setSummary(extracted.summary());
        profile.setLanguage(extracted.language());

        var report = validator.verify(extracted, sourceText);

        List<ExtractedProfile.ExtractedExperience> experiences =
                extracted.experiences() == null ? List.of() : extracted.experiences();
        for (int i = 0; i < experiences.size(); i++) {
            var source = experiences.get(i);
            CvExperience experience = new CvExperience();
            experience.setEmployer(source.employer());
            experience.setTitle(source.title());
            experience.setStartDate(source.startDate());
            experience.setEndDate(source.endDate());
            experience.setCurrent(source.current());
            experience.setLocation(source.location());
            experience.setOrdinal(i);
            report.notesForExperience(i).ifPresentOrElse(note -> {
                experience.setVerified(false);
                experience.setVerificationNotes(note);
            }, () -> experience.setVerified(true));
            profile.addExperience(experience);

            List<String> bullets = source.bullets() == null ? List.of() : source.bullets();
            for (int b = 0; b < bullets.size(); b++) {
                CvExperienceBullet bullet = new CvExperienceBullet();
                bullet.setText(bullets.get(b));
                bullet.setOrdinal(b);
                experience.addBullet(bullet);
            }
        }

        List<ExtractedProfile.ExtractedEducation> education =
                extracted.education() == null ? List.of() : extracted.education();
        for (int i = 0; i < education.size(); i++) {
            var source = education.get(i);
            CvEducation entry = new CvEducation();
            entry.setInstitution(source.institution());
            entry.setDegree(source.degree());
            entry.setFieldOfStudy(source.fieldOfStudy());
            entry.setStartDate(source.startDate());
            entry.setEndDate(source.endDate());
            entry.setOrdinal(i);
            report.notesForEducation(i).ifPresentOrElse(note -> {
                entry.setVerified(false);
                entry.setVerificationNotes(note);
            }, () -> entry.setVerified(true));
            profile.addEducation(entry);
        }

        List<ExtractedProfile.ExtractedSkill> skills =
                extracted.skills() == null ? List.of() : extracted.skills();
        for (int i = 0; i < skills.size(); i++) {
            CvSkill skill = new CvSkill();
            skill.setName(skills.get(i).name());
            skill.setCategory(skills.get(i).category());
            skill.setOrdinal(i);
            profile.addSkill(skill);
        }
    }
}
