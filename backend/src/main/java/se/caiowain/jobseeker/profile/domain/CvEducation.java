package se.caiowain.jobseeker.profile.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "cv_education")
public class CvEducation {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cv_profile_id", nullable = false)
    private CvProfile cvProfile;

    @Column(length = 512) private String institution;
    @Column(length = 512) private String degree;
    @Column(name = "field_of_study", length = 512) private String fieldOfStudy;
    @Column(name = "start_date", length = 32) private String startDate;
    @Column(name = "end_date", length = 32) private String endDate;
    @Column(nullable = false) private int ordinal;
    @Column(nullable = false) private boolean verified = true;
    @Column(name = "verification_notes", columnDefinition = "text") private String verificationNotes;

    public Long getId() { return id; }
    public CvProfile getCvProfile() { return cvProfile; }
    public void setCvProfile(CvProfile cvProfile) { this.cvProfile = cvProfile; }
    public String getInstitution() { return institution; }
    public void setInstitution(String institution) { this.institution = institution; }
    public String getDegree() { return degree; }
    public void setDegree(String degree) { this.degree = degree; }
    public String getFieldOfStudy() { return fieldOfStudy; }
    public void setFieldOfStudy(String fieldOfStudy) { this.fieldOfStudy = fieldOfStudy; }
    public String getStartDate() { return startDate; }
    public void setStartDate(String startDate) { this.startDate = startDate; }
    public String getEndDate() { return endDate; }
    public void setEndDate(String endDate) { this.endDate = endDate; }
    public int getOrdinal() { return ordinal; }
    public void setOrdinal(int ordinal) { this.ordinal = ordinal; }
    public boolean isVerified() { return verified; }
    public void setVerified(boolean verified) { this.verified = verified; }
    public String getVerificationNotes() { return verificationNotes; }
    public void setVerificationNotes(String v) { this.verificationNotes = v; }
}
