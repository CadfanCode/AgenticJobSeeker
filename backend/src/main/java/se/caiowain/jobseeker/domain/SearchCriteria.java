package se.caiowain.jobseeker.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "search_criteria")
public class SearchCriteria {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 512)
    private String query;

    /** Comma-separated JobTech municipality taxonomy codes. */
    @Column(name = "municipality_codes", columnDefinition = "text")
    private String municipalityCodes;

    /** Comma-separated plain municipality names, for client-side filtering of feeds. */
    @Column(name = "municipality_names", columnDefinition = "text")
    private String municipalityNames;

    @Column(name = "occupation_field_codes", columnDefinition = "text")
    private String occupationFieldCodes;

    @Column(nullable = false)
    private boolean enabled = true;

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getQuery() { return query; }
    public void setQuery(String query) { this.query = query; }
    public String getMunicipalityCodes() { return municipalityCodes; }
    public void setMunicipalityCodes(String v) { this.municipalityCodes = v; }
    public String getMunicipalityNames() { return municipalityNames; }
    public void setMunicipalityNames(String v) { this.municipalityNames = v; }
    public String getOccupationFieldCodes() { return occupationFieldCodes; }
    public void setOccupationFieldCodes(String v) { this.occupationFieldCodes = v; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
