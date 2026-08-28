package se.caiowain.jobseeker.tailor.domain;

import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "application_requirement")
public class ApplicationRequirement {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tailored_application_id", nullable = false)
    private TailoredApplication tailoredApplication;

    /** Quoted verbatim from the job ad. Never a claim about the candidate. */
    @Column(nullable = false, columnDefinition = "text") private String text;
    @Column(nullable = false) private int ordinal;
    @Column(name = "over_broad", nullable = false) private boolean overBroad;

    @OneToMany(mappedBy = "applicationRequirement", cascade = CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.EAGER)
    @OrderBy("ordinal ASC")
    private List<ApplicationEvidence> evidence = new ArrayList<>();

    public void addEvidence(ApplicationEvidence e) {
        e.setApplicationRequirement(this);
        evidence.add(e);
    }

    public Long getId() { return id; }
    public TailoredApplication getTailoredApplication() { return tailoredApplication; }
    public void setTailoredApplication(TailoredApplication a) { this.tailoredApplication = a; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public int getOrdinal() { return ordinal; }
    public void setOrdinal(int ordinal) { this.ordinal = ordinal; }
    public boolean isOverBroad() { return overBroad; }
    public void setOverBroad(boolean overBroad) { this.overBroad = overBroad; }
    public List<ApplicationEvidence> getEvidence() { return evidence; }
}
