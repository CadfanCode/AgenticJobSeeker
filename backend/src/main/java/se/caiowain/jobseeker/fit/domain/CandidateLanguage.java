package se.caiowain.jobseeker.fit.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "candidate_language")
public class CandidateLanguage {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "job_preferences_id", nullable = false)
    private JobPreferences preferences;

    @Column(nullable = false, length = 8) private String language;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private LanguageLevel level = LanguageLevel.NONE;

    @Column(nullable = false) private int ordinal;

    public Long getId() { return id; }
    public JobPreferences getPreferences() { return preferences; }
    public void setPreferences(JobPreferences preferences) { this.preferences = preferences; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public LanguageLevel getLevel() { return level; }
    public void setLevel(LanguageLevel level) { this.level = level; }
    public int getOrdinal() { return ordinal; }
    public void setOrdinal(int ordinal) { this.ordinal = ordinal; }
}
