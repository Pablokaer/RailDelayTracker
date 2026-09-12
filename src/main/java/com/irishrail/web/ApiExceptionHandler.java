package com.irishrail.web;

import com.irishrail.controller.ApiController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Turns bad input into a 400 with an RFC 7807 body.
 *
 * <p>Scoped to {@link ApiController} on purpose: the server-rendered pages must keep returning
 * HTML, and an unscoped {@code @RestControllerAdvice} would answer a broken page request with
 * JSON.
 *
 * <p>What this replaces: a {@code parseDate} helper that caught every exception and returned null.
 * {@code ?from=nonsense} therefore did not fail — it silently became "no lower bound", which the
 * aggregate layer widens to the year 2000, so a typo quietly ran the most expensive query in the
 * application and returned an answer that looked legitimate.
 */
@RestControllerAdvice(assignableTypes = ApiController.class)
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(InvalidRequestException.class)
    public ProblemDetail onInvalidRequest(InvalidRequestException e) {
        return badRequest(e.getMessage());
    }

    /** A {@code from}/{@code to} that is not an ISO date, or a non-numeric {@code limit}. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail onTypeMismatch(MethodArgumentTypeMismatchException e) {
        String expected = e.getRequiredType() == null ? "a different type" : e.getRequiredType().getSimpleName();
        return badRequest("'" + e.getName() + "' could not be read as " + expected
                + (LocalDateHint.applies(e) ? " (expected format: YYYY-MM-DD)" : ""));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail onMissingParameter(MissingServletRequestParameterException e) {
        return badRequest("'" + e.getParameterName() + "' is required");
    }

    /**
     * Anything unforeseen. The message is logged rather than returned: an upstream stack trace or
     * a SQL fragment in a public response body tells a caller more about this system than it should.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail onUnexpected(Exception e) {
        log.error("Unhandled error serving an API request", e);
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setTitle("Internal error");
        problem.setDetail("The request could not be completed.");
        return problem;
    }

    private static ProblemDetail badRequest(String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Invalid request");
        problem.setDetail(detail);
        return problem;
    }

    private static final class LocalDateHint {
        static boolean applies(MethodArgumentTypeMismatchException e) {
            return e.getRequiredType() == java.time.LocalDate.class;
        }
    }
}
