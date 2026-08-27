package se.caiowain.jobseeker.profile.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import se.caiowain.jobseeker.profile.UploadRejectedException;
import se.caiowain.jobseeker.profile.extract.ExtractionUnavailableException;
import se.caiowain.jobseeker.profile.extract.PdfTextExtractionException;

@RestControllerAdvice
public class ProfileExceptionHandler {

    @ExceptionHandler(UploadRejectedException.class)
    ProblemDetail rejected(UploadRejectedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** A scanned PDF is a valid file we cannot use — 422, not 400. */
    @ExceptionHandler(PdfTextExtractionException.class)
    ProblemDetail unreadable(PdfTextExtractionException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(ExtractionUnavailableException.class)
    ProblemDetail unavailable(ExtractionUnavailableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail illegalState(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }
}
