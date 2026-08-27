package se.caiowain.jobseeker.profile.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import se.caiowain.jobseeker.profile.CvProfileService;
import se.caiowain.jobseeker.profile.CvUploadService;
import se.caiowain.jobseeker.profile.UploadRejectedException;
import se.caiowain.jobseeker.profile.api.dto.*;
import se.caiowain.jobseeker.profile.domain.*;
import se.caiowain.jobseeker.profile.extract.PdfTextExtractor;
import se.caiowain.jobseeker.profile.repo.CvDocumentRepository;
import se.caiowain.jobseeker.profile.repo.CvProfileRepository;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final CvUploadService uploads;
    private final CvProfileService profileService;
    private final PdfTextExtractor pdfTextExtractor;
    private final CvDocumentRepository documents;
    private final CvProfileRepository profiles;

    public ProfileController(CvUploadService uploads, CvProfileService profileService,
                             PdfTextExtractor pdfTextExtractor, CvDocumentRepository documents,
                             CvProfileRepository profiles) {
        this.uploads = uploads;
        this.profileService = profileService;
        this.pdfTextExtractor = pdfTextExtractor;
        this.documents = documents;
        this.profiles = profiles;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ProfileDto> upload(@RequestParam("file") MultipartFile file) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new UploadRejectedException("Could not read the uploaded file");
        }

        CvDocument document = uploads.store(file.getOriginalFilename(), file.getContentType(), content);

        // Re-uploading identical bytes must not re-extract. Beyond wasting a paid model
        // call, a second extraction would replace corrections already made against the
        // first one. Return the existing profile with 200 instead of 201.
        var existing = profiles.findFirstByCvDocumentIdOrderByIdDesc(document.getId());
        if (existing.isPresent()) {
            return ResponseEntity.ok(toDto(existing.get()));
        }

        if (document.getExtractedText() == null || document.getExtractedText().isBlank()) {
            document.setExtractedText(pdfTextExtractor.extract(content));
            document = documents.save(document);
        }

        CvProfile profile = profileService.ingest(document);
        return ResponseEntity.status(HttpStatus.CREATED).body(toDto(profile));
    }

    @GetMapping
    public ResponseEntity<ProfileDto> current() {
        return profileService.current()
                .map(p -> ResponseEntity.ok(toDto(p)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/source-text", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> sourceText() {
        return profileService.current()
                .map(p -> ResponseEntity.ok(p.getCvDocument().getExtractedText()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping
    public ProfileDto save(@RequestBody ProfileDto request) {
        return toDto(profileService.applyCorrections(fromDto(request)));
    }

    @PostMapping("/reextract")
    public ProfileDto reextract() {
        return toDto(profileService.reextract());
    }

    @PostMapping("/approve")
    public ProfileDto approve() {
        return toDto(profileService.approve());
    }

    private static ProfileDto toDto(CvProfile p) {
        List<ExperienceDto> experiences = p.getExperiences().stream()
                .map(e -> new ExperienceDto(e.getId(), e.getEmployer(), e.getTitle(),
                        e.getStartDate(), e.getEndDate(), e.isCurrent(), e.getLocation(),
                        e.getOrdinal(), e.isVerified(), e.getVerificationNotes(),
                        e.getBullets().stream()
                                .map(b -> new BulletDto(b.getId(), b.getText(), b.getOrdinal()))
                                .toList()))
                .toList();

        List<EducationDto> education = p.getEducation().stream()
                .map(e -> new EducationDto(e.getId(), e.getInstitution(), e.getDegree(),
                        e.getFieldOfStudy(), e.getStartDate(), e.getEndDate(), e.getOrdinal(),
                        e.isVerified(), e.getVerificationNotes()))
                .toList();

        List<SkillDto> skills = p.getSkills().stream()
                .map(s -> new SkillDto(s.getId(), s.getName(), s.getCategory(), s.getOrdinal()))
                .toList();

        return new ProfileDto(p.getId(), p.getFullName(), p.getHeadline(), p.getEmail(),
                p.getPhone(), p.getLocation(), p.getSummary(), p.getLanguage(),
                p.getStatus().name(), p.getModelUsed(), p.getExtractedAt(), p.getReviewedAt(),
                p.getCvDocument() == null ? null : p.getCvDocument().getFilename(),
                experiences, education, skills);
    }

    /** Builds a detached profile carrying the reviewer's edits, for the service to apply. */
    private static CvProfile fromDto(ProfileDto dto) {
        CvProfile profile = new CvProfile();
        profile.setFullName(dto.fullName());
        profile.setHeadline(dto.headline());
        profile.setEmail(dto.email());
        profile.setPhone(dto.phone());
        profile.setLocation(dto.location());
        profile.setSummary(dto.summary());
        profile.setLanguage(dto.language());

        if (dto.experiences() != null) {
            for (ExperienceDto e : dto.experiences()) {
                CvExperience experience = new CvExperience();
                experience.setEmployer(e.employer());
                experience.setTitle(e.title());
                experience.setStartDate(e.startDate());
                experience.setEndDate(e.endDate());
                experience.setCurrent(e.current());
                experience.setLocation(e.location());
                experience.setOrdinal(e.ordinal());
                experience.setVerified(e.verified());
                experience.setVerificationNotes(e.verificationNotes());
                profile.addExperience(experience);
                if (e.bullets() != null) {
                    for (BulletDto b : e.bullets()) {
                        CvExperienceBullet bullet = new CvExperienceBullet();
                        bullet.setText(b.text());
                        bullet.setOrdinal(b.ordinal());
                        experience.addBullet(bullet);
                    }
                }
            }
        }
        if (dto.education() != null) {
            for (EducationDto e : dto.education()) {
                CvEducation entry = new CvEducation();
                entry.setInstitution(e.institution());
                entry.setDegree(e.degree());
                entry.setFieldOfStudy(e.fieldOfStudy());
                entry.setStartDate(e.startDate());
                entry.setEndDate(e.endDate());
                entry.setOrdinal(e.ordinal());
                entry.setVerified(e.verified());
                entry.setVerificationNotes(e.verificationNotes());
                profile.addEducation(entry);
            }
        }
        if (dto.skills() != null) {
            for (SkillDto s : dto.skills()) {
                CvSkill skill = new CvSkill();
                skill.setName(s.name());
                skill.setCategory(s.category());
                skill.setOrdinal(s.ordinal());
                profile.addSkill(skill);
            }
        }
        return profile;
    }
}
