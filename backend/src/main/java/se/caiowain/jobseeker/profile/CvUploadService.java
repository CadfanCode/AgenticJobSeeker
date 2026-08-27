package se.caiowain.jobseeker.profile;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import se.caiowain.jobseeker.profile.domain.CvDocument;
import se.caiowain.jobseeker.profile.repo.CvDocumentRepository;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

@Service
public class CvUploadService {

    private final CvDocumentRepository documents;
    private final long maxBytes;

    public CvUploadService(CvDocumentRepository documents,
                           @Value("${jobseeker.profile.max-upload-bytes:10485760}") long maxBytes) {
        this.documents = documents;
        this.maxBytes = maxBytes;
    }

    /**
     * Stores the upload, or returns the existing document when the same bytes were already
     * uploaded. Idempotency matters: an accidental double upload must not discard the
     * corrections already made against the first one.
     */
    @Transactional
    public CvDocument store(String filename, String contentType, byte[] content) {
        if (content == null || content.length == 0) {
            throw new UploadRejectedException("The uploaded file is empty");
        }
        if (content.length > maxBytes) {
            throw new UploadRejectedException(
                    "The file is too large: " + content.length + " bytes, limit is " + maxBytes);
        }
        boolean looksLikePdf = (contentType != null && contentType.toLowerCase().contains("pdf"))
                || (filename != null && filename.toLowerCase().endsWith(".pdf"));
        if (!looksLikePdf) {
            throw new UploadRejectedException("Only PDF uploads are accepted");
        }

        String hash = sha256(content);
        return documents.findBySha256(hash).orElseGet(() -> {
            CvDocument document = new CvDocument();
            document.setFilename(filename == null ? "cv.pdf" : filename);
            document.setContentType(contentType == null ? "application/pdf" : contentType);
            document.setSizeBytes(content.length);
            document.setSha256(hash);
            document.setContent(content);
            document.setUploadedAt(Instant.now());
            return documents.save(document);
        });
    }

    public String sha256(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
