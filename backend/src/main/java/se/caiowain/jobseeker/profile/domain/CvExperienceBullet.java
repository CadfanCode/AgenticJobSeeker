package se.caiowain.jobseeker.profile.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "cv_experience_bullet")
public class CvExperienceBullet {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cv_experience_id", nullable = false)
    private CvExperience cvExperience;

    @Column(nullable = false, columnDefinition = "text") private String text;
    @Column(nullable = false) private int ordinal;

    public Long getId() { return id; }
    public CvExperience getCvExperience() { return cvExperience; }
    public void setCvExperience(CvExperience cvExperience) { this.cvExperience = cvExperience; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public int getOrdinal() { return ordinal; }
    public void setOrdinal(int ordinal) { this.ordinal = ordinal; }
}
