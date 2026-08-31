package se.caiowain.jobseeker.archive.api;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import se.caiowain.jobseeker.api.dto.PageDto;
import se.caiowain.jobseeker.archive.ArchiveService;
import se.caiowain.jobseeker.archive.api.dto.ArchiveDetailDto;
import se.caiowain.jobseeker.archive.api.dto.ArchiveSummaryDto;
import se.caiowain.jobseeker.archive.domain.ApplicationArchive;
import se.caiowain.jobseeker.archive.repo.ApplicationArchiveRepository;

/**
 * Preview, approve and read the archive. The approve mapping lives here rather than in
 * TailoringController so that {@code archive} depends on {@code tailor} and never the reverse.
 */
@RestController
public class ArchiveController {

    private final ArchiveService service;
    private final ApplicationArchiveRepository archives;

    public ArchiveController(ArchiveService service, ApplicationArchiveRepository archives) {
        this.service = service;
        this.archives = archives;
    }

    /** The exact HTML the renderer prints. Same method the approve path calls. */
    @GetMapping(value = "/api/applications/{id}/preview/cv", produces = MediaType.TEXT_HTML_VALUE)
    public String previewCv(@PathVariable Long id) {
        return service.cvHtml(id);
    }

    @GetMapping(value = "/api/applications/{id}/preview/letter", produces = MediaType.TEXT_HTML_VALUE)
    public String previewLetter(@PathVariable Long id) {
        return service.letterHtml(id);
    }

    /** Renders both documents, so it takes seconds rather than milliseconds. */
    @PostMapping("/api/applications/{id}/approve")
    public ArchiveDetailDto approve(@PathVariable Long id) {
        return toDetail(service.approve(id));
    }

    @GetMapping("/api/archive")
    public PageDto<ArchiveSummaryDto> list(@RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return PageDto.of(
                archives.findAllByOrderByApprovedAtDesc(PageRequest.of(page, Math.min(size, 100))),
                ArchiveController::toSummary);
    }

    @GetMapping("/api/archive/{id}")
    public ResponseEntity<ArchiveDetailDto> detail(@PathVariable Long id) {
        return archives.findById(id)
                .map(a -> ResponseEntity.ok(toDetail(a)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/api/archive/{id}/cv.pdf")
    public ResponseEntity<byte[]> cvPdf(@PathVariable Long id) {
        return archives.findById(id)
                .map(a -> pdf(a.getCvPdf(), "cv.pdf"))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/api/archive/{id}/letter.pdf")
    public ResponseEntity<byte[]> letterPdf(@PathVariable Long id) {
        return archives.findById(id)
                .map(a -> pdf(a.getLetterPdf(), "letter.pdf"))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static ResponseEntity<byte[]> pdf(byte[] bytes, String filename) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition", "inline; filename=\"" + filename + "\"")
                .body(bytes);
    }

    private static ArchiveSummaryDto toSummary(ApplicationArchive a) {
        return new ArchiveSummaryDto(a.getId(), a.getJobTitle(), a.getEmployerName(),
                a.getCoveragePercent(), a.getApprovedAt());
    }

    private static ArchiveDetailDto toDetail(ApplicationArchive a) {
        return new ArchiveDetailDto(a.getId(), a.getJobTitle(), a.getEmployerName(),
                a.getJobCanonicalUrl(), a.getJobApplyUrl(), a.getJobDescriptionText(),
                a.getLetterText(), a.getCoveragePercent(), a.getRenderedBy(), a.getApprovedAt(),
                a.getCvPdfSha256(), a.getLetterPdfSha256());
    }
}
