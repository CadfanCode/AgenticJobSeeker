package se.caiowain.jobseeker.fit.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What you will accept. A singleton, seeded by V7 so the form always has a row to edit.
 *
 * <p>Deliberately separate from {@code CvProfile}: that records what a document says about
 * you and is rewritten whenever a CV is re-extracted. These are decisions, and an
 * extraction must never be able to overwrite them.
 */
@Entity
@Table(name = "job_preferences")
public class JobPreferences {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "home_municipality", length = 128) private String homeMunicipality;

    /** Comma-separated plain names, matching {@code search_criteria.municipality_names}. */
    @Column(name = "acceptable_municipalities", columnDefinition = "text")
    private String acceptableMunicipalities;

    @Enumerated(EnumType.STRING)
    @Column(name = "remote_policy", nullable = false, length = 32)
    private RemotePolicy remotePolicy = RemotePolicy.HYBRID_OK;

    @Column(name = "deal_breakers", columnDefinition = "text") private String dealBreakers;

    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    @OneToMany(mappedBy = "preferences", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("ordinal ASC")
    private List<CandidateLanguage> languages = new ArrayList<>();

    public void addLanguage(CandidateLanguage language) {
        language.setPreferences(this);
        languages.add(language);
    }

    /** Splits on commas and drops blanks, so an unset field yields an empty list. */
    public List<String> acceptableMunicipalityList() {
        if (acceptableMunicipalities == null || acceptableMunicipalities.isBlank()) {
            return List.of();
        }
        return Arrays.stream(acceptableMunicipalities.split(","))
                .map(String::strip)
                .filter(name -> !name.isEmpty())
                .toList();
    }

    public Long getId() { return id; }
    public String getHomeMunicipality() { return homeMunicipality; }
    public void setHomeMunicipality(String v) { this.homeMunicipality = v; }
    public String getAcceptableMunicipalities() { return acceptableMunicipalities; }
    public void setAcceptableMunicipalities(String v) { this.acceptableMunicipalities = v; }
    public RemotePolicy getRemotePolicy() { return remotePolicy; }
    public void setRemotePolicy(RemotePolicy remotePolicy) { this.remotePolicy = remotePolicy; }
    public String getDealBreakers() { return dealBreakers; }
    public void setDealBreakers(String dealBreakers) { this.dealBreakers = dealBreakers; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public List<CandidateLanguage> getLanguages() { return languages; }
}
