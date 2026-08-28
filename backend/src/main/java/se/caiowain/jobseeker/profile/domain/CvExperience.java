package se.caiowain.jobseeker.profile.domain;

import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "cv_experience")
public class CvExperience {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cv_profile_id", nullable = false)
    private CvProfile cvProfile;

    @Column(length = 512) private String employer;
    @Column(length = 512) private String title;
    /** Stored exactly as written on the CV. Never parsed into a date. */
    @Column(name = "start_date", length = 32) private String startDate;
    @Column(name = "end_date", length = 32) private String endDate;
    @Column(name = "is_current", nullable = false) private boolean current;
    @Column(length = 256) private String location;
    @Column(nullable = false) private int ordinal;
    @Column(nullable = false) private boolean verified = true;
    @Column(name = "verification_notes", columnDefinition = "text") private String verificationNotes;

    @OneToMany(mappedBy = "cvExperience", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("ordinal ASC")
    private List<CvExperienceBullet> bullets = new ArrayList<>();

    public void addBullet(CvExperienceBullet b) { b.setCvExperience(this); bullets.add(b); }

    public Long getId() { return id; }
    public CvProfile getCvProfile() { return cvProfile; }
    public void setCvProfile(CvProfile cvProfile) { this.cvProfile = cvProfile; }
    public String getEmployer() { return employer; }
    public void setEmployer(String employer) { this.employer = employer; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getStartDate() { return startDate; }
    public void setStartDate(String startDate) { this.startDate = startDate; }
    public String getEndDate() { return endDate; }
    public void setEndDate(String endDate) { this.endDate = endDate; }
    public boolean isCurrent() { return current; }
    public void setCurrent(boolean current) { this.current = current; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public int getOrdinal() { return ordinal; }
    public void setOrdinal(int ordinal) { this.ordinal = ordinal; }
    public boolean isVerified() { return verified; }
    public void setVerified(boolean verified) { this.verified = verified; }
    public String getVerificationNotes() { return verificationNotes; }
    public void setVerificationNotes(String v) { this.verificationNotes = v; }
    public List<CvExperienceBullet> getBullets() { return bullets; }
}
