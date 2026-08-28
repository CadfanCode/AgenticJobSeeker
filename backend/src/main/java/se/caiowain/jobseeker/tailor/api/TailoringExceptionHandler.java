package se.caiowain.jobseeker.tailor.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import se.caiowain.jobseeker.tailor.ApplicationAlreadyApprovedException;
import se.caiowain.jobseeker.tailor.ProfileNotReadyException;
import se.caiowain.jobseeker.tailor.TailoringRejectedException;
import se.caiowain.jobseeker.tailor.TailoringUnavailableException;

@RestControllerAdvice
public class TailoringExceptionHandler {

    @ExceptionHandler(ProfileNotReadyException.class)
    ProblemDetail notReady(ProfileNotReadyException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(TailoringUnavailableException.class)
    ProblemDetail unavailable(TailoringUnavailableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    /** The guards rejected the model twice. 422: the request was fine, the output was not. */
    @ExceptionHandler(TailoringRejectedException.class)
    ProblemDetail rejected(TailoringRejectedException e) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        detail.setProperty("violations", e.getViolations());
        return detail;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail notFound(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ApplicationAlreadyApprovedException.class)
    ProblemDetail alreadyApproved(ApplicationAlreadyApprovedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}
