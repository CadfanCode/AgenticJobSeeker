package se.caiowain.jobseeker.tailor.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "application_evidence")
public class ApplicationEvidence {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_requirement_id", nullable = false)
    private ApplicationRequirement applicationRequirement;

    /** Nullable on purpose: the bullet may be deleted after the application is approved. */
    @Column(name = "cv_experience_bullet_id")
    private Long cvExperienceBulletId;

    /** The snapshot. This is what makes an approved application immutable. */
    @Column(name = "bullet_text", nullable = false, columnDefinition = "text")
    private String bulletText;

    @Column(nullable = false) private int ordinal;

    public Long getId() { return id; }
    public ApplicationRequirement getApplicationRequirement() { return applicationRequirement; }
    public void setApplicationRequirement(ApplicationRequirement r) { this.applicationRequirement = r; }
    public Long getCvExperienceBulletId() { return cvExperienceBulletId; }
    public void setCvExperienceBulletId(Long id) { this.cvExperienceBulletId = id; }
    public String getBulletText() { return bulletText; }
    public void setBulletText(String bulletText) { this.bulletText = bulletText; }
    public int getOrdinal() { return ordinal; }
    public void setOrdinal(int ordinal) { this.ordinal = ordinal; }
}
