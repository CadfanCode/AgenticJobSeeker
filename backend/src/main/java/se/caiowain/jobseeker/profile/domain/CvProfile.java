package se.caiowain.jobseeker.profile.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "cv_profile")
public class CvProfile {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "cv_document_id", nullable = false)
    private CvDocument cvDocument;

    @Column(name = "full_name", length = 256) private String fullName;
    @Column(length = 512) private String headline;
    @Column(length = 256) private String email;
    @Column(length = 64) private String phone;
    @Column(length = 256) private String location;
    @Column(columnDefinition = "text") private String summary;
    @Column(length = 8) private String language;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ProfileStatus status = ProfileStatus.NEEDS_REVIEW;

    @Column(name = "model_used", length = 64) private String modelUsed;
    @Column(name = "extracted_at") private Instant extractedAt;
    @Column(name = "reviewed_at") private Instant reviewedAt;

    @OneToMany(mappedBy = "cvProfile", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("ordinal ASC")
    private List<CvExperience> experiences = new ArrayList<>();

    @OneToMany(mappedBy = "cvProfile", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("ordinal ASC")
    private List<CvEducation> education = new ArrayList<>();

    @OneToMany(mappedBy = "cvProfile", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("ordinal ASC")
    private List<CvSkill> skills = new ArrayList<>();

    public void addExperience(CvExperience e) { e.setCvProfile(this); experiences.add(e); }
    public void addEducation(CvEducation e) { e.setCvProfile(this); education.add(e); }
    public void addSkill(CvSkill s) { s.setCvProfile(this); skills.add(s); }

    public Long getId() { return id; }
    public CvDocument getCvDocument() { return cvDocument; }
    public void setCvDocument(CvDocument cvDocument) { this.cvDocument = cvDocument; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getHeadline() { return headline; }
    public void setHeadline(String headline) { this.headline = headline; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public ProfileStatus getStatus() { return status; }
    public void setStatus(ProfileStatus status) { this.status = status; }
    public String getModelUsed() { return modelUsed; }
    public void setModelUsed(String modelUsed) { this.modelUsed = modelUsed; }
    public Instant getExtractedAt() { return extractedAt; }
    public void setExtractedAt(Instant extractedAt) { this.extractedAt = extractedAt; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
    public List<CvExperience> getExperiences() { return experiences; }
    public List<CvEducation> getEducation() { return education; }
    public List<CvSkill> getSkills() { return skills; }
}
