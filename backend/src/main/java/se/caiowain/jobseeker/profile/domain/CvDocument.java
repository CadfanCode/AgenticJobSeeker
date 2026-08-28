package se.caiowain.jobseeker.profile.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "cv_document")
public class CvDocument {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 512)
    private String filename;

    @Column(name = "content_type", nullable = false, length = 128)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false, unique = true, length = 64)
    private String sha256;

    /** Plain byte[] maps to PostgreSQL bytea; @Lob would map to oid (large object). */
    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] content;

    @Column(name = "extracted_text", columnDefinition = "text")
    private String extractedText;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    public Long getId() { return id; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public byte[] getContent() { return content; }
    public void setContent(byte[] content) { this.content = content; }
    public String getExtractedText() { return extractedText; }
    public void setExtractedText(String extractedText) { this.extractedText = extractedText; }
    public Instant getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(Instant uploadedAt) { this.uploadedAt = uploadedAt; }
}
